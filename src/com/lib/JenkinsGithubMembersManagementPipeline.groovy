package com.lib
import groovy.json.JsonSlurper

def runPipeline() {

  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def google_bucket_name = ""
  def jenkins_job_log = ""
  def allTeams        = ['basic', 'contributors', 'pro', 'premium', 'admin']

  // // Getting common functions from jenkins global library
  def commonFunctions        = new CommonFunction()

  // // Get username who run the job 
  def triggerUser            = commonFunctions.getBuildUser()
  
  // Making sure that jenkins is using by default CST time 
  def timeStamp = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))

  node('master') {
  // Getting the base domain name from Jenkins master < example: fuchicorp.com >
  google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
  }

  try {

    def slavePodTemplate = """
        metadata:
          labels:
            k8s-label: ${k8slabel}
          annotations:
            jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
        spec:
          affinity:
            podAntiAffinity:
              requiredDuringSchedulingIgnoredDuringExecution:
              - labelSelector:
                  matchExpressions:
                  - key: component
                    operator: In
                    values:
                    - jenkins-jenkins-master
                topologyKey: "kubernetes.io/hostname"
          containers:
          - name: fuchicorptools
            image: fuchicorp/buildtools
            imagePullPolicy: Always
            command:
            - cat
            tty: true
            volumeMounts:
              - mountPath: /etc/secrets/service-account/
                name: google-service-account
          - name: python
            image: python:latest
            imagePullPolicy: IfNotPresent
            command:
            - cat
            tty: true
            volumeMounts:
              - mountPath: /var/run/docker.sock
                name: docker-sock
              - mountPath: /etc/secrets/service-account/
                name: google-service-account
          serviceAccountName: common-service-account
          securityContext:
            runAsUser: 0
            fsGroup: 0
          volumes:
            - name: google-service-account
              secret:
                secretName: google-service-account
            - name: docker-sock
              hostPath:
                path: /var/run/docker.sock
    """
    properties([
      parameters([
        booleanParam(defaultValue: false, 
        description: 'Please select to onboard the new Members to Fuchicorp', 
        name: 'ON_BOARD_TO_FUCHICORP'),       
        
        booleanParam(defaultValue: false, 
        description: 'Please select to offboard Members from Fuchicorp', 
        name: 'OFF_BOARD_FROM_FUCHICORP'),
        
        booleanParam(defaultValue: false, 
        description: 'Please select to switch Fuchicorp Membership ', 
        name: 'CHANGE_MEMBERSHIP_TO'),
        
        choice(name: 'MEMBERSHIP', 
        choices: allTeams, 
        description: 'Please select a Membership'),
        
        text(defaultValue: '', 
        description: 'Please enter github user per line to onboard to the organization', 
        name: 'GITHUB_USERNAME', 
        trim: true), 
        
        gitParameter(branch: '', branchFilter: 'origin/(.*)', defaultValue: "master", 
        description: 'Please select the branch you would like to build ', 
        name: 'GIT_BRANCH', quickFilterEnabled: true, 
        selectedValue: 'NONE', sortMode: 'NONE', tagFilter: '*', type: 'PT_BRANCH')
      ])
    ])
    
    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: false) {
      node(k8slabel){
        try {
        timestamps {
        container("python") {
          withCredentials([usernamePassword(credentialsId: 'github-common-access', passwordVariable: 'GIT_TOKEN', usernameVariable: 'GIT_USERNAME')]) {
            stage("Pull Repo"){
              git branch: "${params.GIT_BRANCH}", credentialsId: 'github-common-access', url: 'https://github.com/fuchicorp/common_scripts.git'
            }
            stage("Getting Configurations"){
              println("Getting into the proper directory")
              dir('github-management/manage-users') {
                if (commonFunctions.isAdmin(triggerUser)) { 
                  stage("Checking Previleges"){
                    echo "You have Admin privilege!!"
                    echo "You are allowed to run this Job!!"
                  }
                  stage("Installing Packages"){
                    sh 'pip3 install -r requirements.txt'
                  }
                  if (params.ON_BOARD_TO_FUCHICORP) {  
                    stage("Onboarding Members"){           
                      println("Sending invites starts now -> Members are being onboarded to Fuchicorp from Github")
                      sh "python3 manage-github.py --invite"
                      println("Process is COMPLETE!!")
                    }
                  } else if (params.OFF_BOARD_FROM_FUCHICORP) {
                    stage("Offboarding Members"){
                      println("Offboarding starts now -> Members are being offboarded from Fuchicorp")
                      sh "python3 manage-github.py --delete"
                      println("Process is COMPLETE!!")
                    }
                  } else if (params.CHANGE_MEMBERSHIP_TO) {
                    stage("Changing Members Membership to $MEMBERSHIP"){
                      println("Membership Change start now -> Members are being Added to Fuchicorp $MEMBERSHIP Membership")
                      sh "python3 manage-github.py --changeUserTeam"
                      println("Process is COMPLETE!!")
                    }
                  } else {
                    stage("No Selection"){
                      println("You did not make any selection. Please choose to invite, delete, addUserTeam or removeUserTeam members from Fuchicorp")
                    }
                  }
                } else {
                    echo "Aborting... Requires Admin Access"
                    currentBuild.result = 'ABORTED'
                    error('You are not allowed to run this Jenkins Job.Please respect Fuchicorp Policies!!!')
                  }
                }  
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
    println("ERROR Detected:")
    println("Log was not uploaded to google cloud")
    println(e.getMessage())
  }
}

return this