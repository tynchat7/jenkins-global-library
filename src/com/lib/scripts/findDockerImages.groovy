
def getFindDockerImageScript(domain_name, deployment_name) {
    def findDockerImageScript = '''
        import groovy.json.JsonSlurper
        def findDockerImages(branchName, domain_name) {
        def versionList = []
        def token       = ""
        def myJsonreader = new JsonSlurper()
        def nexusData = myJsonreader.parse(new URL("https://nexus.${domain_name}/service/rest/v1/components?repository=fuchicorp"))
        nexusData.items.each { if (it.name.contains(branchName)) { versionList.add(it.name + ":" + it.version) } }
        while (true) {
            if (nexusData.continuationToken) {
            token = nexusData.continuationToken
            nexusData = myJsonreader.parse(new URL("https://nexus.${domain_name}/service/rest/v1/components?repository=fuchicorp&continuationToken=${token}"))
            nexusData.items.each { if (it.name.contains(branchName)) { versionList.add(it.name + ":" + it.version) } }
            }
            if (nexusData.continuationToken == null ) { break } }
        if(!versionList) { versionList.add("ImmageNotFound") } 
        return versionList.reverse(true) }
        def domain_name     = "%s"
        def deployment_name = "%s"
        findDockerImages(deployment_name, domain_name)
        '''
    return String.format(findDockerImageScript, domain_name, deployment_name)
}
    

println(getFindDockerImageScript('fuchicorp.com', 'academy'))