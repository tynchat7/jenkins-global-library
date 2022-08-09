#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  def commonFunctions        = new CommonFunction()
  def triggerUser            = commonFunctions.getBuildUser()
  def branch                 = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def gitUrl                 = "${scm.getUserRemoteConfigs()[0].getUrl()}"
  def k8slabel               = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  // def allEnvironments        = ['dev', 'qa', 'test', 'stage', 'prod']
  def domain_name            = ""
  def google_bucket_name     = ""
  def jenkins_job_log        = ""
  def deployment_tfvars      = ""
  // def google_project_id      = ""


  // Making sure that jenkins is using by default CST time 
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))
  def deploymentName = "${JOB_NAME}".split('/')[0].replace('-fuchicorp', '').replace('-build', '').replace('-deploy', '')
  

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      domain_name = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim()
      // google_project_id = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()
  }

  try {

    properties([

      parameters([
        // Boolean Paramater for terraform apply or not 
        booleanParam(defaultValue: false, 
        description: 'Apply All Changes', 
        name: 'terraform_apply'),

        // Boolean Paramater for terraform destroy 
        booleanParam(defaultValue: false, 
        description: 'Destroy deployment', 
        name: 'terraform_destroy'),

         // Gets GKE Project ID
        string(description: 'Please enter packer Google Project Id', 
        name: 'project_id', trim: true),

        // User input for packer ami id
        string(description: 'Please enter Packer Ami Id', 
        name: 'packer_ami_id', trim: true),

        // Get bucket name
        string(description: 'Please enter Bucket Name', 
        name: 'bucket_name', trim: true),

        // Get Cluster credentials
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', 
        defaultValue: '', description: 'Please select Google Credential', name: 'google_credentials', required: true)

      ])
    ])

    podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: false) {
      node(k8slabel) {
        try {
        timestamps {
          container('fuchicorptools') {
            stage("Pulling the code") {
              checkout scm
              gitCommitHash = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
            }

            withCredentials(
              [usernamePassword(credentialsId: 'github-common-access', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USERNAME')]) {

                withCredentials([string(credentialsId: 'google_credentials', variable: 'credentials')]) {
                    writeFile file: '/root/google-credentials.json', text: "${credentials}"

                stage('Generate Configurations') {
                  
                  // Generating all tfvars for the application
                  deployment_tfvars += """
                    deployment_name           = "${deploymentName}"
                    deployment_environment    = "tools"
                    google_domain_name        = "${domain_name}"
                    google_bucket_name        = "${params.bucket_name}"
                    google_project_id         = "${params.project_id}\"
                    ami_id                    = "${params.packer_ami_id}"
                    git_common_token          = "${GIT_TOKEN}"
                  """.stripIndent()

                  writeFile(
                    [file: "deployment_configuration.tfvars", text: "${deployment_tfvars}"]
                  )
                }

                stage('Terraform Apply/Plan') {
                  if (!params.terraform_destroy) {
                    if (params.terraform_apply) {
                      echo "##### Terraform Applying the Changes ####"
                      sh """#!/bin/bash
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"

                        echo "Generating public key"
                        echo -e "\n"|ssh-keygen -t rsa -N ""

                        echo "Running terraform apply"
                        echo | terraform apply --auto-approve --var-file="\$DATAFILE"
                      """
                    } else {
                      echo "##### Terraform Plan the Changes ####"
                      sh """#!/bin/bash
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"

                        echo "Generating public key"
                        echo -e "\n"|ssh-keygen -t rsa -N ""

                        echo "Running terraform plan"
                        terraform plan --var-file="\$DATAFILE"
                      """
                    }
                  }
                }

                stage('Terraform Destroy') {
                  if (!params.terraform_apply) {
                    if (params.terraform_destroy) {
                      echo "##### Terraform Destroying ####"
                      sh """#!/bin/bash
                        echo "Running set environment script!!"
                        source "./set-env.sh" "deployment_configuration.tfvars"

                        echo "Generating public key"
                        echo -e "\n"|ssh-keygen -t rsa -N ""
                        
                        echo "Running terraform destroy"
                        echo | terraform destroy --auto-approve -var-file="\$DATAFILE"
                      """
                    }
                  }
                }

                if (params.terraform_destroy) {
                  if (params.terraform_apply) {
                    println("""
                      Sorry you can not destroy and apply at the same time
                    """)
                    currentBuild.result = 'FAILURE'
                  }
                }   
              commonFunctions.uploadLog(google_bucket_name)
            }            
          }
        }
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
    println("ERROR Detected:")
    println("Log was not uploaded to google cloud")
    println(e.getMessage())
  }
}

return this
