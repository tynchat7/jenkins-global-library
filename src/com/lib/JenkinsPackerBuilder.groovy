#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  def commonFunctions     = new CommonFunction()
  def packer_init_script  = ''
  def triggerUser         = commonFunctions.getBuildUser()
  def branch              = "${scm.branches[0].name}".replaceAll(/^\*\//, '')
  def k8slabel            = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def google_bucket_name     = ""
  def jenkins_job_log = ""
  def timeStamp           = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
  }

  // Generating the repository name 
  def repositoryName = "${JOB_NAME}"
      .split('/')[0]
      .replace('-fuchicorp', '')
      .replace('-build', '')
      .replace('-deploy', '')

  //  Parameters for Google project ID and for Credentials files
   properties([
    parameters([
      string(defaultValue: '', description: 'please provide your Google project ID to build AMI ', name: 'google_project_id', trim: true), 
      // Get Cluster credentials
      credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', 
      defaultValue: '', description: 'Please select Google Credential', name: 'google-credentials', required: true)
    ])
  ])

  try {
    podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: false) {
        
      node(k8slabel) {
        try {
        timestamps {
          container('fuchicorptools') {
            stage("Pulling the code") {
              checkout scm
            }

            // Entering to packer script folder in the project
            dir("${WORKSPACE}/packer-scripts/") {

              // Creating credentials.json  to authenticate google 
              withCredentials([string(credentialsId: 'google-credentials', variable: 'credentials')]) {
                writeFile file: 'credentials.json', text: "${credentials}"
                }

                // Added creating env session
                 env.GOOGLE_APPLICATION_CREDENTIALS = "${WORKSPACE}/packer-scripts/credentials.json"
                 env.GOOGLE_PROJECT_ID = "${params.google_project_id}"


              stage("Packer Validate") {
                println('Validating the syntax.')
                sh 'packer validate -syntax-only script.json'
                // sh 'sleep 300'
                println('Validating the packer code.')
                sh "packer validate -var google_project_id=${params.google_project_id} -var google_creds=credentials.json script.json"
              }

              stage("Packer Build") {
                println('Building the packer.')
                sh "packer build -var google_project_id=${params.google_project_id} -var google_creds=credentials.json script.json"
              }
            }  
          commonFunctions.uploadLog(google_bucket_name)                 
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
    println("ERROR Detected: ${e.getMessage()}")
   }
}

return this