# CommonFunction strucure
#fuchicorp/work-sessions/jenkins-global-library


## getSlaveTemplate
Function is responsible to generate pod template 
Default Container: fuchicorp/buildtools:latest

##### Supported containers
1. docker
2. packer
3. python
4. sonar

## How to use? 
```
getSlaveTemplate(podRandomNamme, [LIST-OF-CONTAINERS])
```