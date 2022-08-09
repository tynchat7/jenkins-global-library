import java.util.regex.Pattern
def environment = ""
def repositoryName = "isitup"
// def branch = "dev-feature" 
def branch = "v12.111" 
// def branch = "master" 



if (branch =~ '^v[0-9].[0-9]' || branch =~ '^v[0-9][0-9].[0-9]' ) {
    environment = 'prod' 

} else if (branch.contains('dev-feature')) {
    environment = 'dev' 
    repositoryName = repositoryName + '-dev-feature'

} else if (branch.contains('qa-feature')) {
    repositoryName = repositoryName + '-qa-feature'
    environment = 'qa' 

} else if (branch.contains('PR')) {
    repositoryName = repositoryName + '-pr-feature'
    environment = 'test' 
    branch = 'master'

} else if (branch == 'master') {
    environment = 'stage' 
} 



println(
    [
        "environment": environment,
        "branch" : branch,
        "repositoryName": repositoryName
    ]
)


// v0.1 v0.2 v0.3 
// tags -> prod 
// master  -> stage 
// qa-feature/something -> qa
// dev-feature/something -> dev 
// pr-121212 -> test 