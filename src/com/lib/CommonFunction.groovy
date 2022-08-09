#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath

def isAdmin(username) {
    def instance = Jenkins.getInstance()
    return instance.getAuthorizationStrategy().getACL(User.get(username))
    .hasPermission(User.get(username).impersonate(), hudson.model.Hudson.ADMINISTER)
}


def scheduleBaseJobs(String baseName, String jobName) {
  /* If Job name contains 'base' and branch name is master or develop
  * scheduleBaseJobs schedule the job to run from 1 through 6- name: sh
         
  */

  if (baseName.contains('base'))  {
    if (jobName == 'master' || jobName == 'develop') {
      properties([[$class: 'RebuildSettings',
      autoRebuild: false,
      rebuildDisabled: false],
      // “At minute 0 past every hour from 1 through 6.”
      pipelineTriggers([cron('0 1-6 * * *')])])
    }
  }
}

// Read Json File
def readJsonFile(jsonFilePath) {

    def myJsonReader            = new JsonSlurper()
    def inputFile               = new File(jsonFilePath)
    return myJsonReader.parseText(inputFile.text)
}

// Read Json String 
def readJson(json){
    def myJsonReader            = new JsonSlurper()
    return myJsonReader.parseText(json)
}

def validateDeployment(username, environment, debugMode) {
  // Making sure that only admin can run the jobs in debug mode!!
  if (debugMode) {
    if (isAdmin(username)) {
      println('WARNING: Running the Jenkins job in debug mode')
    } else {
      currentBuild.result = 'ABORTED' 
      error("ERROR: You do not have admin access to run jobs in debug mode!!")
    }
  } 

  // Making sure only admin can go to prod!!
  if (isAdmin(username)) {
      println("You are allowed to do prod deployments!!")
  } else {
    if (environment in ['dev', 'qa', 'test', 'stage', 'tools']) {
      println("You are allowed to do non-prod deployments!!")

    } else {
      currentBuild.result = 'ABORTED' 	      
      error('You are not allowed to do prod deployments!!')
    }
  }
}

def getApprovalFromAdmin(username){
  if (isAdmin(username)) {
    println("You already are admin No need approvals")
  } else {
    println("This job is required admin approval")
    isApproved = input(
      id: 'someId',
      message: 'Approve?',
      submitter: 'admin', // who is approving the job
        parameters: [choice(
              choices: ['No', 'Yes'],
              description: 'Only Admins can approve this job',
              name: 'Would you like to proceed this job?')]
    ) == 'Yes'
  }  
}

// Function to get user id 
@NonCPS
def getBuildUser() {
      try {
        return currentBuild.rawBuild.getCause(Cause.UserIdCause).getUserId()
      } catch (e) {
        def user = "AutoTrigger"
        return user
      }
  }

// Function to upload log
def uploadLog(google_bucket_name) {
  //Shorten version of date to for log
  def date = Calendar.getInstance().format('YYY-MM-dd')
  //pulls the raw log of output
  try {
    def baos = new ByteArrayOutputStream()
    currentBuild.rawBuild.getLogText().writeLogTo(0, baos)
    jenkins_job_log = baos.toString()
  } catch(e) {
      println(e.getMessage())
  }
  //creates log and copies it to gcloud storage bucket
      container('fuchicorptools') {
      writeFile( [file: "current-job.log", text: "${jenkins_job_log}"] )
      sh """
      gcloud auth activate-service-account --key-file=/etc/secrets/service-account/credentials.json
      gsutil cp current-job.log gs://${google_bucket_name}/JENKINS_LOGS/${JOB_NAME}/${date}/${JOB_NAME}-${date}-${BUILD_NUMBER}.txt
      """
      }
}

// Create SonarQube Project 
def createProject(projectKey, projectName, url) {
    def jsonSlurper = new JsonSlurper()
    def data = new URL("${url}/api/projects/create?project=${projectKey}&name=${projectName}").openConnection();
        data.setRequestMethod("POST")
        data.setRequestProperty("Content-Type", "application/json")
    
    
    if (data.responseCode == 200) {
      def responseData = jsonSlurper.parseText(data.getInputStream().getText())
      return responseData.project.key.toString()
    } else {
      return null
    }
}

// Generating the token to run the sonar scan
def genToken(tokenName, url, username, password) {
    def jsonSlurper = new JsonSlurper()
    def data = new URL("${url}/api/user_tokens/generate?name=${tokenName}").openConnection();
        data.setRequestProperty("Authorization", "Basic " + "${username}:${password}".bytes.encodeBase64().toString())
        data.setRequestProperty("Content-Type", "application/json")
        data.setRequestMethod("POST")
    
    if (data.responseCode == 200) {
      def responseData = jsonSlurper.parseText(data.getInputStream().getText())
      return responseData.token.toString()
    } else {
      return null
    }
}

// call this function with required argeument k8slabel and it will give slave template 
def getSlavePodTemplate(k8slabel, List<String> inputContainers) {

    // FuchiCorp Main container 
    def fuchicorpContainer = """
        - name: fuchicorptools
          image: fuchicorp/buildtools:latest
          imagePullPolicy: Always
          command: ["/bin/bash","-c","bash /scripts/Dockerfile/set-config.sh && cat"]
          tty: true
          volumeMounts:
            - mountPath: /etc/secrets/service-account/
              name: google-service-account         
    """
    // Docker container 
    def docker = """
        - name: docker
          image: docker:19.03-git
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-daemon
    """
    // Packer container 
    def packer = """
        - name: packer
          image: hashicorp/packer:latest
          imagePullPolicy: IfNotPresent
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
    """
    // SonarQube Scaner container
    def sonar = """
        - name: sonar-scanner
          image: sonarsource/sonar-scanner-cli
          imagePullPolicy: Always
          command:
          - cat
          tty: true
    """
    // Python container
    def python = """
        - name: python
          image: python:latest
          imagePullPolicy: IfNotPresent
          command:
          - cat
          tty: true
    """
    // Kaniko container 
    def kaniko = """
        - name: kaniko
          image: gcr.io/kaniko-project/executor:debug
          imagePullPolicy: Always
          command: 
          - /busybox/cat
          tty: true
          volumeMounts:
            - name: jenkins-docker-kaniko-cfg
              mountPath: /kaniko/.docker
    """
    // Main template for the Jenkins slave
    def outputTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
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
    """

    if ( "docker" in inputContainers ) {
      outputTemplate = outputTemplate + docker
    }
    if ( "packer" in inputContainers ) {
      outputTemplate = outputTemplate + packer
    }
    if ( "sonar-scanner" in inputContainers ) {
      outputTemplate = outputTemplate + sonar
    }
    if ( "python" in inputContainers ) {
      outputTemplate = outputTemplate + python
    }
    if ( "kaniko" in inputContainers ) {
      outputTemplate = outputTemplate + kaniko
    }
    if (!inputContainers || "fuchicorptools" in inputContainers ) {
      outputTemplate = outputTemplate + fuchicorpContainer
    }

    outputTemplate = outputTemplate + """
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
          - name: docker-daemon
            hostPath:
              path: /var/run/docker.sock    
          - name: jenkins-docker-kaniko-cfg
            projected:
              sources:
              - secret:
                  name: nexus-creds
                  items:
                    - key: .dockerconfigjson
                      path: config.json
    """

    return outputTemplate
}

// Function which will return groovy script as a string getFindDockerImageScript('academy')
def getFindDockerImageScript(deployment_name) {
    def findDockerImageScript = '''
        import groovy.json.JsonSlurper
        def findDockerReposUrls() {
            def nexusInternalUrl = 'http://nexus-tools-nexus-repository-manager:8081'
            def myJsonreader            = new JsonSlurper()
            def foundReposUrls          = []
            def reposUrl                = "${nexusInternalUrl}/service/rest/v1/repositories"
            def nexusResponse           = new URL(reposUrl).openConnection();
                nexusResponse.setRequestProperty("Content-Type", "application/json")
            def jsonData = myJsonreader.parseText(nexusResponse.getInputStream().getText())
            jsonData.each() {
                if (it.format == 'docker') {
                    foundReposUrls.add("${nexusInternalUrl}/service/rest/v1/components?repository=${it.name}")
                }
            }
            return foundReposUrls
        }

        def findDockerImages(deploymentName) {
            def token                   = ''
            def foundDockerImages       = []
            def myJsonreader            = new JsonSlurper()

            findDockerReposUrls().each() { dockerUrl ->
                def nexusDockerResponse           = new URL(dockerUrl).openConnection();
                    nexusDockerResponse.setRequestProperty("Content-Type", "application/json")

                def jsonData = myJsonreader.parseText(nexusDockerResponse.getInputStream().getText())
                jsonData.items.each { docker -> 
                    if (docker.name.contains(deploymentName)) {
                        foundDockerImages.add(docker.name + ":" + docker.version) 
                    }
                }

                while (true) {
                    if (jsonData.continuationToken) {
                        token = jsonData.continuationToken
                        nexusDockerResponse           = new URL(dockerUrl + "&continuationToken=${token}").openConnection();
                            nexusDockerResponse.setRequestProperty("Content-Type", "application/json")
                        jsonData = myJsonreader.parseText(nexusDockerResponse.getInputStream().getText()) 
                        jsonData.items.each { 
                            if (it.name.contains(deploymentName)) { 
                                foundDockerImages.add(it.name + ":" + it.version) 
                                } 
                            }
                    }
                    if (jsonData.continuationToken == null) { break } 
                }
            }

            if (!foundDockerImages) {
                foundDockerImages.add('docker-image-not-found')
            }

            return foundDockerImages
        }
        def deploymentName     = "%s"
        findDockerImages(deploymentName)
        '''
    return String.format(findDockerImageScript, deployment_name)
}

// Function to find all tags from docker hub 
def findDockerHubImages(nameApp) {
  def foundRepo    = ""
  def token        = ""
  def myJsonreader = new JsonSlurper()
  def nexusData = myJsonreader.parse(new URL("https://registry.hub.docker.com/v2/repositories/fuchicorp/"))
  def repoVersions = []

  nexusData.results.each {
    if (it.name ==  nameApp ) {
        foundRepo = it.name
      }
    }

  nexusData = myJsonreader.parse(new URL("https://registry.hub.docker.com/v2/repositories/fuchicorp/${foundRepo}/tags/"))

  nexusData.results.each {
    repoVersions.add( "${foundRepo}:" +  it.name)
  }

  if(!repoVersions) {
    versionList.add('ImmageNotFound')
  }

  return repoVersions.sort()
}

// Function to run terraform properly
def terraformTrrigger(params, debugModeScript) {
    if ( params.terraform_apply && ! params.terraform_destroy ) {
      echo "##### Terraform Applying the Changes ####"
      sh """#!/bin/bash
          ${debugModeScript}
          echo "Running set environment script!!"
          source "./set-env.sh" "deployment_configuration.tfvars"
          ${params.init_commands}
          echo "Running terraform apply"
          echo | terraform apply --auto-approve --var-file="\$DATAFILE"
      """
    } else if ( params.terraform_destroy && ! params.terraform_apply) {
        println "##### Terraform Destroy everything #####"
        sh """#!/bin/bash
          ${debugModeScript}
          echo "Running set environment script!!"
          source "./set-env.sh" "deployment_configuration.tfvars"
          echo "Running terraform destroy"
          echo | terraform destroy --auto-approve -var-file="\$DATAFILE"
        """
    } else if ( ! params.terraform_destroy && ! params.terraform_apply) {
        println "##### Terraform Plan (Check) the Changes #####"
        sh """#!/bin/bash
            ${debugModeScript}
            echo "Running set environment script!!"
            source "./set-env.sh" "deployment_configuration.tfvars"
            echo "Running terraform plan"
            echo | terraform plan --var-file="\$DATAFILE" 
        """
    } else if ( params.terraform_destroy && params.terraform_apply) {
        println "Sorry I ca't do apply and destroy"
    }
}

def terraformSwitch(terraformVersion){
  sh "tfswitch ${terraformVersion}"
}

return this 




// docker login --username admin --password  https://docker.fuchicorp.com

// docker run -ti -v /var/run