def repositoryName = 'academy'
def gitCommitHash = 'ccf891f'


node("master") {
    println("${docker_image}")
    // build job: 'academy-fuchicorp-deploy/master', 
    // parameters: [
    //     [$class: 'BooleanParameterValue', name: 'terraform_apply', value: true],
    //     [$class: 'StringParameterValue', name: 'selectedDockerImage', value: "${repositoryName}:${gitCommitHash}"], 
    //     [$class: 'StringParameterValue', name: 'environment', value: 'dev']
        
    //     ]
    def deploymentName = "academy"
    dir("${WORKSPACE}/deployments/terraform/") {
        try {
            withCredentials([
                file(credentialsId: "academy-config", variable: 'default_config')
            ]) {
                sh """
                cat \$default_config >> ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars
                cat ${WORKSPACE}/deployments/terraform/deployment_configuration.tfvars"""
            }
        
            println("Found default configurations appending to main configuration")
        } catch (e) {
            println("Default configurations inside jenkins secret does not exist. Skiping!!")
        }
    }

}