#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath

def runPipeline() {
  def commonFunctions = new CommonFunction()
  def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def jenkins_job_log = ""

  node('master') {
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
  }

  properties([
      [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false], 
      parameters([
          booleanParam(defaultValue: false, description: 'Please select to apply all changes to the environment', name: 'terraform_apply'), 
          booleanParam(defaultValue: false, description: 'Please select to destroy all changes to the environment', name: 'terraform_destroy'),
      ])
  ])

  try {
      podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['fuchicorptools']), showRawYaml: false) {
        node(k8slabel){ 
          try {
          stage("Pull Repo"){
            git branch: 'master', credentialsId: 'github-common-access', url: 'https://github.com/fuchicorp/cluster-infrastructure.git'
          }
            
          container("fuchicorptools") {
            dir('aws/vpc-project/deployments/terraform') {
              withCredentials([usernamePassword(credentialsId: 'aws-access', passwordVariable: 'AWS_SECRET_ACCESS_KEY', usernameVariable: 'AWS_ACCESS_KEY_ID')]) {
                  stage("Terrraform Init"){
                    sh """
                    source ./setenv.sh rash-vpc.tfvars
                    terraform destroy -var-file rash-vpc.tfvars -auto-approve
                    """
                  }        
                
                  if (terraform_apply.toBoolean()) {
                    stage("Terraform Apply"){
                        println("AWS VPC deployment starting in virginia")
                        sh """
                        terraform apply -var-file rash-vpc.tfvars -auto-approve
                        """
                    }
                  }
                  else if (terraform_destroy.toBoolean()) {
                    stage("Terraform Destroy"){
                      println("AWS VPC to be destroyed in virginia")
                        sh """
                          terraform destroy -var-file rash-vpc.tfvars -auto-approve
                        """
                    }
                  }
                  else {
                    stage("Terraform Plan"){
                      sh """
                      terraform plan -var-file rash-vpc.tfvars
                      echo "Nothinh to do.Please choose either apply or destroy"
                        
                      """
                    }
                  }                                 
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
