#!/usr/bin/env groovy
package com.lib
import groovy.json.JsonSlurper
import hudson.FilePath


def runPipeline() {
  // Defining needed variables to run the Jenkins job properly!!
  def environment         = ""
  def gitCommitHash       = ""
  def domain_name         = ""
  def branch_or_tag       = ""
  def jenkins_job_log     = ""
  def google_project_id   = "" 
  def google_bucket_name  = ""
  def commonFunctions     = new CommonFunction()
  def triggerUser         = commonFunctions.getBuildUser()
  def branch              = "${scm.branches[0].name}".replaceAll(/^\*\//, '')
  def k8slabel            = "jenkins-pipeline-${UUID.randomUUID().toString()}"
  def timeStamp           = Calendar.getInstance().getTime().format('ssmmhh-ddMMYYY',TimeZone.getTimeZone('CST'))

  // Generating the repository name 
  def repositoryName  = "${JOB_NAME}"
      .split('/')[0]
      .replace('-fuchicorp', '')
      .replace('-build', '')
      .replace('-deploy', '')

  // Generating the deployment name example-deploy 
  def deployJobName = "${JOB_NAME}"
      .split('/')[0]
      .replace('-build', '-deploy')

  node('master') {
    
      // Getting the base domain name from Jenkins master < example: fuchicorp.com >
      domain_name   = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
      google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
      google_project_id = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()

      dir("${WORKSPACE}/tag_branch") {
            checkout scm
            branch_or_tag = sh(returnStdout: true, script:
                '''
                    #!/bin/bash -e
                    GIT_REMOTE=$(git remote)
                    if git rev-parse --verify -q refs/remotes/${GIT_REMOTE}/${BRANCH_NAME} > /dev/null
                    then echo branch
                    exit 0
                    elif git rev-parse --verify -q refs/tags/${BRANCH_NAME} > /dev/null
                    then echo tag
                    exit 0
                    else echo unknown
                    exit 0
                    fi
                '''.stripIndent()).trim()
      }
      sh "rm -rf ${WORKSPACE}/tag_branch"
  }

  
  if (branch =~ '^v[0-9].[0-9]' || branch =~ '^v[0-9][0-9].[0-9]' ) {
        if (branch_or_tag == 'tag') {
          // if Application release or branch starts with v* example v0.1 will be deployed to prod
          environment = 'prod' 
          repositoryName = repositoryName + '-prod'
        } else if (branch_or_tag == 'branch') {
          currentBuild.result = 'FAILURE'
          error("Branch should not go to prod environment")
        }

  } else if (branch.contains('dev-feature')) {
        // if branch name contains dev-feature then the deploy will be deployed to dev environment 
        environment = 'dev' 
        repositoryName = repositoryName + '-dev-feature'

  } else if (branch.contains('qa-feature')) {
        // if branch name contains q-feature then the deploy will be deployed to qa environment
        repositoryName = repositoryName + '-qa-feature'
        environment = 'qa' 

  } else if (branch.contains('PR')) {
        // PR means Pull requests all PR will be deployed to test namespace 
        branch = branch.replaceAll('PR-.*', '')
        repositoryName = repositoryName + '-pr-feature'
        environment = 'test' 
  
  } else if (branch == 'master' || branch == 'main') {
        // If branch is master it will be deployed to stage environment 
        environment = 'stage' 
        repositoryName = repositoryName + '-stage'
  } 

  try {
    properties([
      buildDiscarder(
        logRotator(artifactDaysToKeepStr: '',
        artifactNumToKeepStr: '',
        daysToKeepStr: '',
        numToKeepStr: '4')), [$class: 'RebuildSettings', autoRebuild: false, rebuildDisabled: false],
         
      parameters([
        booleanParam(defaultValue: false, description: 'Click this if you would like to deploy to latest', name: 'PUSH_LATEST'),
        booleanParam(defaultValue: false, description: 'Click this if you would like to deploy the image', name: 'BUILD_ONLY'),
        booleanParam(defaultValue: false, description: 'Please select to skip the scaning the source code', name: 'SKIP_SCANNING'),
        booleanParam(defaultValue: false, description: 'Pleas select this to be able to run the on debug mode', name: 'debugMode')
      ])    
    ])

    if (triggerUser != "AutoTrigger") {
      commonFunctions.validateDeployment(triggerUser, environment, params.debugMode)
    } else {
      echo "The job is triggereted automatically and skipped validaton!!"
    }
        

    podTemplate(name: k8slabel, label: k8slabel, yaml: commonFunctions.getSlavePodTemplate(k8slabel, ['docker', 'kaniko', 'sonar-scanner', 'fuchicorptools']), showRawYaml: params.debugMode) {
      node(k8slabel) {
        try {
        timestamps {

          stage("Pulling the code") {
            checkout scm
            gitCommitHash = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
          }

          dir("${WORKSPACE}/deployments/docker") {
            stage('SonarQube Scanning') {
              container('sonar-scanner') {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "sonarqube-admin-access", usernameVariable: 'ADMIN_USER', passwordVariable: 'ADMIN_PASSWORD']]) {
                  if (!params.SKIP_SCANNING) {
                    try {
                      def sonarQubeUrl = "https://sonarqube.${domain_name}"
                      project = commonFunctions.createProject("${repositoryName}-${gitCommitHash}", "${repositoryName}-${gitCommitHash}", sonarQubeUrl)
                      println("Created SonarQube Project <${repositoryName}-${gitCommitHash}>")
                      
                      token = commonFunctions.genToken("${repositoryName}-${gitCommitHash}-${BUILD_NUMBER}", sonarQubeUrl, "${ADMIN_USER}", "${ADMIN_PASSWORD}")
                      println("Created another token for this bild.")

                      sh """
                      #!/bin/bash
                      sonar-scanner \
                        -Dsonar.projectKey="${repositoryName}-${gitCommitHash}" \
                        -Dsonar.sources=. \
                        -Dsonar.host.url="${sonarQubeUrl}" \
                        -Dsonar.login="${token}"
                      """
                    } catch (e) {
                      println("""SonarQube Scaning was not applied with following error""")
                      println(e.getMessage())
                    }
                  } else {
                    println('The SonarQube Scaning was skipped!!')
                  }
                }
              }
            }
            container('kaniko') {
              stage('Kaniko Build/Push Image') {
                // Build the docker image with kaniko
                try {
                  sh """
                  #!/busybox/sh -xe
                    /kaniko/executor --dockerfile ${WORKSPACE}/deployments/docker/Dockerfile --context ${WORKSPACE}/deployments/docker --destination docker.${domain_name}/${repositoryName}:${gitCommitHash}
                  """
                } catch (e) {
                  println("""kaniko build was not applied because of the following error""")
                  println(e.getMessage())
                } 
              }
            }
           }
          
          if (params.BUILD_ONLY) {
            println('''\tThe job is a build only job !!!\nThe image has been built and pushed to Nexus !!!''')
          } else {
            stage("Trigger Deploy") {
              // Triggering the example-deploy job with following params 
              build job: "${deployJobName}", 
              parameters: [
                [$class: 'BooleanParameterValue', name: 'terraform_apply',      value: true],
                [$class: 'BooleanParameterValue', name: 'debugMode',            value: params.debugMode],
                [$class: 'StringParameterValue',  name: 'selectedDockerImage',  value: "${repositoryName}:${gitCommitHash}"],
                [$class: 'StringParameterValue',  name: 'branchName',           value: branch],
                [$class: 'StringParameterValue',  name: 'environment',          value: "${environment}"]
              ]
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
  } catch (logError) {
      currentBuild.result = 'FAILURE'
      println("ERROR: Log was not uploaded to google cloud. ${logError.getMessage()}")
    }
}

return this
