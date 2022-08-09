#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import static groovy.json.JsonOutput.*
import hudson.FilePath

def runPipeline() {
  def commonFunctions     = new CommonFunction()
  def notifier            = new JenkinsNotificator()
  def triggerUser         = commonFunctions.getBuildUser()
  def branch              = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def gitUrl              = "${scm.getUserRemoteConfigs()[0].getUrl()}"
  def k8slabel            = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def deploymentName      = "${JOB_NAME}".split('/')[0].replace('-fuchicorp', '').replace('-build', '').replace('-deploy', '')
  def google_bucket_name  = ""

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
  }

  try {

    // Trying to build the job
    properties([ 
      parameters([
        // Boolean Paramater for terraform apply or not 
        booleanParam(defaultValue: true, 
        description: 'Apply All Changes', 
        name: 'terraform_apply'),

        // Boolean Paramater for terraform destroy 
        booleanParam(defaultValue: false, 
        description: 'Destroy deployment', 
        name: 'terraform_destroy'),
        
        // Branch name to deploy environment 
        gitParameter(branch: '', branchFilter: 'origin/(.*)', defaultValue: 'origin/master', 
        description: 'Please select the branch name to deploy', name: 'branchName', 
        quickFilterEnabled: true, selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH'),

        // Extra configurations to deploy with it 
        text(name: 'deployment_tfvars', 
        defaultValue: 'extra_values = "tools"', 
        description: 'terraform configuration'),

        // Init commands to run before terraform apply 
        text(name: 'init_commands', 
        defaultValue: '', 
        description: 'Please add commands you like to run before terraform apply'),

        // Boolean Paramater for debuging this job 
        booleanParam(defaultValue: false, 
        description: 'If you would like to turn on debuging click this!!', 
        name: 'debugMode')

      ])
    ])

    // Making sure admins is awere what commands is running!!
    if (params.init_commands) {
      if (commonFunctions.isAdmin(triggerUser)) {
        echo 'You have Admin privilege!!'
      } else {
        println("\n\nUser: ${triggerUser}\nTrying to run: ${params.init_commands}\nNeeds approval from admin!!\n\n").stripIndent()
        commonFunctions.getApprovalFromAdmin(triggerUser) 
      }
    }

    if (triggerUser != "AutoTrigger") {
      commonFunctions.validateDeployment(triggerUser, 'tools', params.debugMode)
      
    } else {
      println('The job is triggereted automatically and skiping the validation !!!')
    }

    if (params.debugMode) {
      if (commonFunctions.isAdmin(triggerUser)) {
          sh('''set -ex && export TF_LOG=DEBUG''')
        } else {
          error("ERROR: You don't have admin access to run this job in debug mode!!")
          currentBuild.result = 'FAILURE'
      }
    }


    podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: params.debugMode) {
      node(k8slabel) {
        try {
          ansiColor('xterm') {
            container('fuchicorptools') {
              stage("Deployment Info") {

                // Colecting information to show on stage <Deployment Info>
                println(
                  prettyPrint(
                    toJson([
                      "Deployment" : deploymentName,
                      "User" : triggerUser,
                      "Build": env.BUILD_NUMBER,
                      "DebugMode": params.debugMode 
                    ])
                  )
                )
              }

              stage("Polling SCM") {
                checkout([$class: 'GitSCM', 
                  branches: [[name: branchName]], 
                  doGenerateSubmoduleConfigurations: false, 
                  extensions: [], submoduleCfg: [], 
                  userRemoteConfigs: [[credentialsId: 'github-common-access', url: gitUrl]]])
              }

              stage('Generate Configurations') {

                sh("""
                  cat  /etc/secrets/service-account/credentials.json > /root/google-credentials.json
                  ## This script should move to docker container to set up ~/.kube/config
                  sh /scripts/Dockerfile/set-config.sh
                  """)

                writeFile( [file: "common_tools.tfvars", text: "${deployment_tfvars}"] )

                try {

                  withCredentials([ file(credentialsId: "${deploymentName}-config", variable: 'default_config') ]) {
                    sh("cat \$default_config >> common_tools.tfvars")
                  }

                  println('Found default configurations appanded to main configuration')

                } catch (notFoundError) {
                  println("Default configurations not founds. Skiping!!")
                }

                if (params.debugMode) {
                  sh 'echo ############################################################# && cat common_tools.tfvars && echo #############################################################'
                }

              }
              
              stage('Terraform Apply/Plan/Destroy') {
                if (params.terraform_apply && ! params.terraform_destroy) {

                  notifier.sendMessage('slack', 'APPLIED', 'notifications', null)

                  dir("${WORKSPACE}") {
                    echo "##### Terraform Applying the Changes ####"
                    sh """#!/bin/bash -e
                      source set-env.sh common_tools.tfvars
                      ${params.init_commands}
                      terraform apply --auto-approve -var-file=common_tools.tfvars
                    """
                  }

                }

                if ( params.terraform_destroy && ! params.terraform_apply ) {
                  notifier.sendMessage('slack', 'DESTROYED', 'notifications', null)
                  dir("${WORKSPACE}") {
                    echo "##### Terraform Destroying ####"
                    sh """#!/bin/bash -e
                      ${params.init_commands}
                      # source set-env.sh common_tools.tfvars
                      # terraform destroy --auto-approve -var-file=common_tools.tfvars
                    """
                    currentBuild.result = 'ABORTED'
                    error('Terraform destroy is disabled for now. Please run all destroy from command line.')
                  }
                }

                if ( ! params.terraform_destroy && ! params.terraform_apply ) {
                  dir("${WORKSPACE}") {
                    println("##### Terraform Plan (Check) the Changes ####")
                    sh """#!/bin/bash -e
                      source set-env.sh common_tools.tfvars
                      ${params.init_commands}
                      terraform plan -var-file=common_tools.tfvars
                    """
                  }
                }

                if (params.terraform_destroy && params.terraform_apply) {
                  currentBuild.result = 'FAILURE'
                  error('Sorry you can not destroy and apply at the same time!')
                }
              }
            }
            commonFunctions.uploadLog(google_bucket_name)
          }
        } catch (functionError) {
          currentBuild.result = 'FAILURE'
          println(functionError.getMessage())
          commonFunctions.uploadLog(google_bucket_name)
        }
      }
    } 
  } catch (e) {
      currentBuild.result = 'FAILURE'
      println("ERROR Detected: ${e.getMessage()}")
  }
}

return this
