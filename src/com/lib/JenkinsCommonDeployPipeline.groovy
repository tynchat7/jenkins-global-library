#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import static groovy.json.JsonOutput.*
import hudson.FilePath



def runPipeline() {
  def commonFunctions        = new CommonFunction()
  def triggerUser            = commonFunctions.getBuildUser()
  def branch                 = "${scm.branches[0].name}".replaceAll(/^\*\//, '').replace("/", "-").toLowerCase()
  def gitUrl                 = "${scm.getUserRemoteConfigs()[0].getUrl()}"
  def k8slabel               = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def allEnvironments        = ['dev', 'qa', 'test', 'stage', 'prod']
  def domain_name            = ""
  def google_bucket_name     = ""
  def google_project_id      = ""
  def jenkins_job_log = ""
  def debugModeScript = '''
  set -e
  '''

  // Making sure that jenkins is using by default CST time 
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))
  

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      domain_name         = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
      google_bucket_name  = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
      google_project_id   = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()
  }

  // job name example-fuchicorp-deploy will be < example > 
  def deploymentName = "${JOB_NAME}".split('/')[0].replace('-fuchicorp', '').replace('-build', '').replace('-deploy', '')

  try {    

    properties([

      //Delete old build jobs
      buildDiscarder(
        logRotator(artifactDaysToKeepStr: '',
        artifactNumToKeepStr: '',
        daysToKeepStr: '',
        numToKeepStr: '4')), [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
         
         // Trying to build the job
      parameters([

      // Boolean Paramater for terraform apply or not 
      booleanParam(defaultValue: false, 
      description: 'Apply All Changes', 
      name: 'terraform_apply'),

      // Boolean Paramater for terraform destroy 
      booleanParam(defaultValue: false, 
      description: 'Destroy deployment', 
      name: 'terraform_destroy'),

      // ExtendedChoice Script is getting all jobs based on this application
      extendedChoice(bindings: '', description: 'Please select Terraform version to override the default version', 
      descriptionPropertyValue: '', groovyClasspath: '', 
      groovyScript: '', multiSelectDelimiter: ',', 
      name: 'TF_VERSION', quoteValue: false, 
      value: '1.2.2, 1.2.3, 1.1.9, 1.0.11, 0.15.5, 0.14.11, 0.14.0, 0.13.7, 0.13.6, 0.13.5, 0.12.31, 0.12.0, 0.11.15, 0.11.14, 0.10.8',
      defaultValue: '0.11.15',
      saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', 
      visibleItemCount: 5),

      // ExtendedChoice Script is getting all jobs based on this application
      extendedChoice(bindings: '', description: 'Please select docker image to deploy', 
      descriptionPropertyValue: '', groovyClasspath: '', 
      groovyScript:  commonFunctions.getFindDockerImageScript(deploymentName), multiSelectDelimiter: ',', 
      name: 'selectedDockerImage', quoteValue: false, 
      saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT', 
      visibleItemCount: 5),
      
      // Branch name to deploy environment 
      gitParameter(branch: '', branchFilter: 'origin/(.*)', defaultValue: 'origin/master', 
      description: 'Please select the branch name to deploy', name: 'branchName', 
      quickFilterEnabled: true, selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH_TAG'),
      
      // list of environment getting from <allEnvironments> and defining variable <environment> to deploy 
      choice(name: 'environment', 
      choices: allEnvironments, 
      description: 'Please select the environment to deploy'),

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

      ]
      )])
    
      if (triggerUser != "AutoTrigger") {
        commonFunctions.validateDeployment(triggerUser, params.environment, params.debugMode)
      } else {  
        if (params.debugMode) {
          echo "The job is triggereted automatically in debug mode!!!"
        }
      }

      // Making sure admins is awere what commands is running!!
      if (params.init_commands) {
        if (commonFunctions.isAdmin(triggerUser)) {
          echo "You have Admin privilege!!"
        } else {
          if (params.environment.contains('prod')) {
            println("""User: ${triggerUser}
            Trying to run: ${params.init_commands}
            Needs approval from admin!!""").stripIndent()
            commonFunctions.getApprovalFromAdmin(triggerUser) 
          }
        }
      }

      if (params.debugMode) {
        if (commonFunctions.isAdmin(triggerUser)) {
            debugModeScript += '''
            set -ex
            export TF_LOG=DEBUG
            echo "Running the scripts on Debug mode!!!"
            '''
          } else {
            error("ERROR: You don't have admin access to run this job in debug mode!!")
            currentBuild.result = 'FAILURE'
        }
      }

  podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: params.debugMode) {
      node(k8slabel) {
        try {
        timestamps { 
          stage("Deployment Info") {

            // Colecting information to show on stage <Deployment Info>
            println(prettyPrint(toJson([
              "Environment" : params.nvironment,
              "Deployment" : deploymentName,
              "Builder" : triggerUser,
              "Branch" : branchName,
              "Build": env.BUILD_NUMBER,
              "Debug": params.debugMode
            ])))
          }
        
          container('fuchicorptools') {

            stage("Polling SCM") {
              checkout([$class: 'GitSCM', 
                        branches: [[name: branchName]], 
                        doGenerateSubmoduleConfigurations: false, 
                        extensions: [], submoduleCfg: [], 
                        userRemoteConfigs: [[credentialsId: 'github-common-access', url: gitUrl]]])
            }

          stage('Generate Configurations') {
            sh """
              cat  /etc/secrets/service-account/credentials.json > ${WORKSPACE}/deployments/terraform/fuchicorp-service-account.json
              cat  /etc/secrets/service-account/credentials.json > /root/google-credentials.json
            """
            // Generating all tfvars for the application
            deployment_tfvars += """
              deployment_name        = \"${deploymentName}\"
              deployment_environment = \"${environment}\"
              deployment_image       = \"docker.${domain_name}/${selectedDockerImage}\"
              credentials            = \"./fuchicorp-service-account.json\"
              google_domain_name     = \"${domain_name}\"
              google_bucket_name     = \"${google_bucket_name}\"
              google_project_id      = \"${google_project_id}\"
            """.stripIndent()

            writeFile( [file: "${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars", text: "${deployment_tfvars}"] )

            if (params.debugMode) {
              sh """
                echo #############################################################
                cat ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                echo #############################################################
              """
              debugModeScript += '''
              set -ex
              echo "Running the scripts on Debug mode!!!"
              ''' 
            }
            
            try {
                withCredentials([
                    file(credentialsId: "${deploymentName}-config", variable: 'default_config')
                ]) {
                    sh """
                      #!/bin/bash
                      cat \$default_config >> ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                    """
                }
                println("Found default configurations appanded to main configuration")
            } catch (e) {
                println("Default configurations not found. Skiping!!")
            }

            if (params.debugMode) {
              sh """
              echo ##########################################################################################################################
              cat ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
              echo ##########################################################################################################################
              """
            }
          }


          withCredentials([usernamePassword(credentialsId: 'github-common-access', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USERNAME')]) {
            stage('Terraform Apply/Plan/Destroy') {
              dir("${WORKSPACE}/deployments/terraform/") {
                echo "##### Helm Upgrade ####"
                sh """#!/bin/bash
                    set -o xtrace
                    export chart_version=\$(git describe --long --tag --always)
                    export app_version=\$(git describe --tag --always)
                    export chart_path=\$(find . -iname 'Chart.yaml')
                    sed "/^version/s/.*\$/version: \$chart_version/" -i \$chart_path
                    sed "/^appVersion/s/.*\$/appVersion: \$app_version/" -i \$chart_path
                """.stripIndent()

                //switching the terraform version
                commonFunctions.terraformSwitch(params.TF_VERSION)
                
                //triggering the terraform apply
                commonFunctions.terraformTrrigger(params, debugModeScript)
              }
            }
          commonFunctions.uploadLog(google_bucket_name)
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
}   catch (e) {
    currentBuild.result = 'FAILURE'
    println("ERROR Detected:")
    println("Log was not uploaded to google cloud")
    println(e.getMessage())
  }
}


return this
