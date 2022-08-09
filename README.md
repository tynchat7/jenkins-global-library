

<img src="https://github.com/fuchicorp/jenkins-global-library/blob/master/docs/pictures/automotive.png"  width="250"  />

# FuchiCorp Jenkins Global Library
#FuchiCorp


This page is contains how to use Jenkins Global Library.  You will need to configure the Jenkins Shared library on your Jenkins to be able to use this library.  Use following link to be able to configure [Jenkins Global Library](https://jenkins.io/doc/book/pipeline/shared-libraries/). On FuchiCorp Jenkins it's already configured by this script  [link](https://github.com/fuchicorp/common_tools/blob/2f0639c77c83b8b7b812434ee2681bf0bbd3f8be/charts/jenkins/values.yaml#L246) 

### Supported Languages 
* Python
* Java


Credentials 
`nexus-docker-creds`

## Build and Deploy 
Deploy job is using Kubernetes plugin in jenkins [link](https://github.com/jenkinsci/kubernetes-plugin) make sure 
Docker build plugin in Jenkins [Link](https://jenkins.io/doc/book/pipeline/docker/)

NOTE: Jenkins is discovering tags and branches in order to deploy to below environments accordingly.

```
master > stage
version tag > prod
PR > test
dev-feature > dev
qa-feature > qa
```
### Deployment File and Folder Structure 

Please follow the deployment file structure below. 

NOTE: Ensure all file and folder do not have any whitespaces included before or after their names. This can result in error, for example, "set-env.sh: No such file or directory".  Please reference ticket for further info [Ticket-54](https://github.com/fuchicorp/jenkins-global-library/issues/54).

```
deployments
  > docker
      * app.py
      * Dockerfile
      * requirements.txt
  > terraform
      * main.tf
      * set-env.sh
      * variables.tf
.gitignore
JenkinsBuilder.groovy
JenkinsDeployer.groovy
README.md
```

### Application build process 
To be able to build the application you will need to define class `JenkinsCommonDockerBuildPipeline`  and call a function `runPipeline` You will need to make sure that build job is multi branch job. 

Example code `JenkinsBuilder.groovy`
``` 
@Library('CommonLib@master') _
def common = new com.lib.JenkinsCommonDockerBuildPipeline()
common.runPipeline()
```

You will need to code put `JenkinsBuilder.groovy` in your application.  After you pushed to SCM you will need to create a Jenkins Job with following naming convention `APPNAME-build`.  

![](https://github.com/fuchicorp/jenkins-global-library/blob/master/docs/pictures/jenkin-build.png)


## Application deploy process

To be able to do application deployment you will need to define class `JenkinsCommonDeployPipeline` and call the the function `runPipeline`. 

Example Code `JenkinsDeployer.groovy`
```
@Library('CommonLib@master') _
def common = new com.lib.JenkinsCommonDeployPipeline()
common.runPipeline()
```


Same process you will need to push to SCM and create your Jenkins job with following name `APPNAME-deploy`. 
![](https://github.com/fuchicorp/jenkins-global-library/blob/master/docs/pictures/jenkins-deploy.png)


