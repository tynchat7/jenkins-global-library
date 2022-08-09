#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import static groovy.json.JsonOutput.*
import hudson.FilePath


def runPipeline() {
  def commonFunctions        = new CommonFunction()
  def k8slabel               = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  
  
  properties([
  parameters([
        string(defaultValue: 'fuchicorp-manage', description: "host user", name: 'user'),
        string(defaultValue: 'us-central1-b', description: " bastion zone", name: 'bastion_zone'),
        string(defaultValue: 'bastion-fuchicorp-com', description: "instance name of your gcloud machine", name: 'domain'),
        credentials(credentialType: 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl', 
        defaultValue: '', description: 'Please select Google Credential', name: 'google-credentials', required: true)
      ])
  ])

   podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: false) {
      node(k8slabel) {
        withCredentials([string(credentialsId: 'google-credentials', variable: 'credentials')]) {
          container('fuchicorptools') {
            stage('syncing-with-github') {
              def project_id = commonFunctions.readJson(credentials).project_id
              sh """#!/bin/bash
              echo '$credentials' > credentials.json

              gcloud auth activate-service-account --key-file=credentials.json

              echo 'Setting the google project based on provided credentials.json file'
              gcloud config set project $project_id
              
              gcloud compute ssh ${user}@${domain} --zone=$bastion_zone --command='source ~/.zshrc && cd /common_scripts/bastion-scripts/ && sudo GIT_TOKEN=\$GIT_TOKEN python3 sync-users.py --refresh' 
              """
            }
          }
        }
      }
    }
}
return this
