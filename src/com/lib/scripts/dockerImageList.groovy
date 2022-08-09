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

def deploymentName = "academy"
println(findDockerImages(deploymentName))