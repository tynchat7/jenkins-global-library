package com.lib
import groovy.json.JsonSlurper

def runPipeline() {

  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def jenkins_job_log = ""
  // Getting common functions from jenkins global library
  def commonFunctions        = new CommonFunction()

  // Get username who run the job 
  def triggerUser            = commonFunctions.getBuildUser()

  node('master') {
    // Getting the base domain name from Jenkins master < example: fuchicorp.com >
    google_bucket_name = sh(returnStdout: true, script: 'println($GOOGLE_BUCKET_NAME').trim())
  }

  properties([
    parameters([
      gitParameter(
        branch: '', branchFilter: 'origin/(.*)', defaultValue: 'master', 
        description: 'Please select the branch you would like to build ', 
        name: 'GIT_BRANCH', quickFilterEnabled: true, 
        selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH'
      ),
      booleanParam(defaultValue: false, description: 'Do you want to sync all labels? ', name: 'SYNC_LABELS'),
      booleanParam(defaultValue: false, description: 'Do you want to !!DELETE ALL LABELS!!? ', name: 'DELETE_ALL_LABELS'),
      booleanParam(defaultValue: false, description: 'Do you want to delete all none managed labels?', name: 'DELETE_NONE_MANAGED_LABELS'),
    ]) 
  ])


  try {

    podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['python']), showRawYaml: false) {
      node(k8slabel) {
        try {
          container("python"){
            withCredentials([usernamePassword(credentialsId: 'github-common-access', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USERNAME')]) {
              stage("Pull SCM") {
                git branch: "${params.GIT_BRANCH}", credentialsId: 'github-common-access', url: 'https://github.com/fuchicorp/common_scripts.git'
              }
              
              stage("Installing Packages") {
                  sh 'pip3 install -r  github-management/manage-labels/requirements.txt'
              }
              
              stage("Running Script") {
                if (params.SYNC_LABELS) {
                  println("Checking for Admin privilege")
                    if (commonFunctions.isAdmin(triggerUser)) {

                      println("Starting creating/syncing process")
                      sh 'python3 github-management/manage-labels/sync-create-github-labels.py'

                    } else {

                      currentBuild.result = 'ABORTED'
                      error('You are not allowed to sync labels!!!')
                    }
                } else {
                  println("No seletion made. Skipping this stage!")
                }
              }

              stage("Deleting all labels") {
                if (params.DELETE_ALL_LABELS) {
                  println("Checking for Admin privilege")
                    if (commonFunctions.isAdmin(triggerUser)) {     

                      println("You have Admin privilege!! Starting Deletion of Labels...")
                      sh 'python3 github-management/manage-labels/delete-github-labels.py --delete yes'

                    } else {

                      currentBuild.result = 'ABORTED'
                      error('You are not allowed to delete all labels!!!')

                    }
                } else {
                  println("No selection made. Skipping this stage!")
                }
              }              

              stage("Deleting None Managed Labels") {
                if (params.DELETE_NONE_MANAGED_LABELS) {
                  println("Checking for Admin privilege")
                    if (commonFunctions.isAdmin(triggerUser)) {
                      println("Deleting all labels which is not inside labels.json file...")
                      sh 'python3 github-management/manage-labels/delete-not-managed-labels.py --delete yes'
                      println("Deleting none managed labels is COMPLETE!!")
                    } else {
                      currentBuild.result = 'ABORTED'
                      error('You are not allowed to delete labels!!!')
                    }
                } else {
                  println("No selection made. Skipping this stage!")
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
    println("ERROR Detected:")
    println("Log was not uploaded to google cloud")
    println(e.getMessage())
   }
}

return this