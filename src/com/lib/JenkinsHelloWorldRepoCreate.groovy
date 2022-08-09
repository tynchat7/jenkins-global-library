// PLEASE FOLLOW THESE DIRECTIONS TO DEPLOY HELLO-WORLD REPO
// https://github.com/fuchicorp/jenkins-global-library/wiki/Create-Hello-World-application-Repo
 
properties([ 
    parameters([
        booleanParam(defaultValue: true, description: 'Please select if you would like apply changes', name: 'apply'),
        booleanParam(defaultValue: false, description: 'Please select if you would like to destroy everything', name: 'destroy'),
        string(defaultValue: 'hello-world', description: 'Provide repo name:', name: 'repo_name', trim: true),
        string(defaultValue: '', description: 'Provide github username:', name: 'github_username', trim: true),
        string(defaultValue: '', description: 'Provide github token:', name: 'github_token', trim: true),
    ])
])

if (params.github_username == '') {
    error("Please make sure you provide github username.")
}

if (params.github_token == '') {
    error("Please make sure you provide github token.")
}

podTemplate(showRawYaml: false) {
    node(POD_LABEL) {
        stage('Generate/Deploy') {
            if (params.apply) {
                try {
                    sh """
                    #!/bin/bash
                    curl -u none:${params.github_token}  https://api.github.com/user/repos -d '{"name":"${params.repo_name}", "auto_init" : false, "private": true}' > /dev/null 
                    """
                    } catch (e) {
                        println(e.getMessage())
                    }
                    
                dir("${WORKSPACE}/fuchicorp") {
                    sh """
                        #!/bin/bash
                        ## Clone the FuchiCorp hello world repo and change remote to users
                        git clone https://github.com/fuchicorp/hello-world.git 
                    """
                }

                dir("${WORKSPACE}/${params.github_username}") {
                    sh """
                        ## Clone users repo
                        git clone https://none:${params.github_token}@github.com/${params.github_username}/${params.repo_name}.git && cd ${params.repo_name}
                        mv ${WORKSPACE}/fuchicorp/hello-world/* ./
                        
                        ## Configure git command and push to main branch
                        git init 
                        git add --all .
                        git config --global user.email "${params.email}"
                        git config --global user.name "${params.github_username}"
                        git commit -m "Created the FuchiCorp hello world application"
                        git push --set-upstream origin main || git push --set-upstream origin master
                    """
                }
            }
            if (params.destroy) {
                sh """curl -X DELETE -H "Accept: application/vnd.github.v3+json" -u none:${params.github_token} https://api.github.com/repos/${params.github_username}/${params.repo_name}
                """
            }
        }
        stage('User Info') {
            if (params.apply){
                println("""Hello ${params.github_username},
                Please navigate to following link to see your ready hello world app
                https://github.com/${params.github_username}/${params.repo_name}.git
                """.replace('    ', ''))
            }
            if (params.destroy) {
                println("""Hello ${params.github_username},
                Please double check the that was deleted from your account by navigating below link.
                https://github.com/${params.github_username}/${params.repo_name}.git
                """.replace('    ', ''))
            }
        }
    }
}

// ## cd ${params.repo_name} && find deployments/terraform/charts/hello-world/ -type f -exec sed -i "s/name: hello-world/name: ${params.repo_name}/g" {} \\;