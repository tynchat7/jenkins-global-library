import jenkins.model.Jenkins
import hudson.security.SecurityRealm
import org.jenkinsci.plugins.GithubSecurityRealm
import jenkins.plugins.git.GitSCMSource
import jenkins.plugins.git.traits.BranchDiscoveryTrait
import org.jenkinsci.plugins.workflow.libs.GlobalLibraries
import org.jenkinsci.plugins.workflow.libs.LibraryConfiguration
import org.jenkinsci.plugins.workflow.libs.SCMSourceRetriever
import net.sf.json.JSONObject
import hudson.*
import hudson.security.*
import jenkins.model.*
import java.util.*
import com.michelin.cio.hudson.plugins.rolestrategy.*
import com.synopsys.arc.jenkins.plugins.rolestrategy.*
import java.lang.reflect.*
import java.util.logging.*
import groovy.json.*
import groovy.json.JsonSlurper
import jenkins.model.Jenkins

if(!binding.hasVariable('github_realm')) {
    github_realm = [:]
}

if(!(github_realm instanceof Map)) {
    throw new Exception('github_realm must be a Map.')
}

String git_hub_auth_id  = ""
String git_hub_auth_secret  = ""
gitToken                   = ""

// gitToken = System.getenv().get("GIT_TOKEN")

/**
  Function to compare if the two global shared libraries are equal.
  */
boolean isLibrariesEqual(List lib1, List lib2) {
    lib1.size() == lib2.size() &&
    !(
        false in [lib1, lib2].transpose().collect { l1, l2 ->
            def s1 = l1.retriever.scm
            def s2 = l2.retriever.scm
            l1.retriever.class == l2.retriever.class &&
            l1.name == l2.name &&
            l1.defaultVersion == l2.defaultVersion &&
            l1.implicit == l2.implicit &&
            l1.allowVersionOverride == l2.allowVersionOverride &&
            l1.includeInChangesets == l2.includeInChangesets &&
            s1.remote == s2.remote &&
            s1.credentialsId == s2.credentialsId &&
            s1.traits.size() == s2.traits.size() &&
            !(
                false in [s1.traits, s2.traits].transpose().collect { t1, t2 ->
                    t1.class == t2.class
                }
            )
        }
    )
}

pipeline_shared_libraries = [
    'CommonLib': [
        'defaultVersion': 'master',
        'implicit': true,
        'allowVersionOverride': true,
        'includeInChangesets': false,
        'scm': [
            'remote': 'https://github.com/fuchicorp/jenkins-global-library.git',
            'credentialsId': 'github-common-access'
        ]
    ]
]


if(!binding.hasVariable('pipeline_shared_libraries')) {
    pipeline_shared_libraries = [:]
}

if(!pipeline_shared_libraries in Map) {
    throw new Exception("pipeline_shared_libraries must be an instance of Map but instead is instance of: "+ pipeline_shared_libraries.getClass())
}

pipeline_shared_libraries = pipeline_shared_libraries as JSONObject

List libraries = [] as ArrayList
pipeline_shared_libraries.each { name, config ->
    if(name && config && config in Map && 'scm' in config && config['scm'] in Map && 'remote' in config['scm'] && config['scm'].optString('remote')) {
        def scm = new GitSCMSource(config['scm'].optString('remote'))
        scm.credentialsId = config['scm'].optString('credentialsId')
        scm.traits = [new BranchDiscoveryTrait()]
        def retriever = new SCMSourceRetriever(scm)
        def library = new LibraryConfiguration(name, retriever)
        library.defaultVersion = config.optString('defaultVersion')
        library.implicit = config.optBoolean('implicit', false)
        library.allowVersionOverride = config.optBoolean('allowVersionOverride', true)
        library.includeInChangesets = config.optBoolean('includeInChangesets', true)
        libraries << library
    }
}

def global_settings = Jenkins.instance.getExtensionList(GlobalLibraries.class)[0]

if(libraries && !isLibrariesEqual(global_settings.libraries, libraries)) {
    global_settings.libraries = libraries
    global_settings.save()
    println 'Configured Pipeline Global Shared Libraries:\n    ' + global_settings.libraries.collect { it.name }.join('\n    ')
}
else {
    if(pipeline_shared_libraries) {
        println 'Nothing changed.  Pipeline Global Shared Libraries already configured.'
    }
    else {
        println 'Nothing changed.  Skipped configuring Pipeline Global Shared Libraries because settings are empty.'
    }
}

github_realm = github_realm as JSONObject

String githubWebUri = github_realm.optString('web_uri', GithubSecurityRealm.DEFAULT_WEB_URI)
String githubApiUri = github_realm.optString('api_uri', GithubSecurityRealm.DEFAULT_API_URI)
String oauthScopes = github_realm.optString('oauth_scopes', GithubSecurityRealm.DEFAULT_OAUTH_SCOPES)
String clientID = github_realm.optString('client_id', git_hub_auth_id)
String clientSecret = github_realm.optString('client_secret', git_hub_auth_secret)

if(!Jenkins.instance.isQuietingDown()) {
    if(clientID && clientSecret) {
        SecurityRealm github_realm = new GithubSecurityRealm(githubWebUri, githubApiUri, clientID, clientSecret, oauthScopes)
        //check for equality, no need to modify the runtime if no settings changed
        if(!github_realm.equals(Jenkins.instance.getSecurityRealm())) {
            Jenkins.instance.setSecurityRealm(github_realm)
            println 'Security realm configuration has changed.  Configured GitHub security realm.'
        } else {
            println 'Nothing changed.  GitHub security realm already configured.'
        }
    }
} else {
    println 'Shutdown mode enabled.  Configure GitHub security realm SKIPPED.'
}

def env = System.getenv()
jsonSlurper = new JsonSlurper()


def getTeamId(teamName) {
  /*
    Function to find teams ID
  */
  def organization = "fuchicorp"
  def teamsUrl = "https://api.github.com/orgs/" + organization + "/teams"
  def teamId = null

  def get = new URL(teamsUrl).openConnection();
      get.setRequestMethod("GET")
      get.setRequestProperty("Authorization", "token " + gitToken)
      get.setRequestProperty("Content-Type", "application/json")

  def data = jsonSlurper.parseText(get.getInputStream().getText())

  data.each() {
    if (it.name.toLowerCase() == teamName.toLowerCase()) {
      teamId = it.id
    }
  }

  return teamId
}


def getTeamMembers(teamName) {
  /*
  Function to find team members from github
  */
  def getTeamId = getTeamId(teamName)
  def totalUsers = []
  def memberUrl = ""
  def pageCount = 1

  while (true) {
    // While loop to go each pages and get all members from team 
    memberUrl = "https://api.github.com/teams/" + getTeamId + "/members?page=" + pageCount
    def get = new URL(memberUrl).openConnection();
      get.setRequestMethod("GET")
      get.setRequestProperty("Authorization", "token "+ gitToken)
      get.setRequestProperty("Content-Type", "application/json")
    def object = jsonSlurper.parseText(get.getInputStream().getText())

    //  Braking the while loop when no one found in the page
    if (! object.login) {
      break;
    }

    // Adding list of found people to totalUsers
    object.login.each{ totalUsers.add(it.toLowerCase()) }
    pageCount = pageCount + 1
  }
  return totalUsers
}

def buildersMembers = []
def readersMembers = []
def adminsMembers = []

  //   Adding QA and DEV team to builder access
  try {
    buildersMembers.addAll(getTeamMembers("Dev"))
  } catch (e) {
    println("detected error" + e)
  }

  try {
    buildersMembers.addAll(getTeamMembers("QA"))
  } catch (e) { println("detected error" + e) }
  try {
    readersMembers.addAll(getTeamMembers("members"))
  } catch (e) { println("detected error" + e) }

  try {
    adminsMembers.addAll(getTeamMembers("devops"))
  } catch (e) { println("detected error" + e) }

  try {
    adminsMembers.addAll(getTeamMembers("jenkins-admin"))
  } catch (e) { println("detected error" + e) }


def globalRoleRead = "read"
def globalBuildRole = "build"
def globalRoleAdmin = "admin"

/**
  *           Users and Groups
  */
def access = [
  admins: adminsMembers,// Using DevOps team from FuchiCorp organization
  builders: buildersMembers,
  readers: readersMembers
]


if (env.AUTHZ_JSON_FILE)  {
  println "Get role authorizations from file " + env.AUTHZ_JSON_FILE
  File f = new File(env.AUTHZ_JSON_FILE)
  def jsonSlurper = new JsonSlurper()
  def jsonText = f.getText()
  access = jsonSlurper.parseText( jsonText )
}
else if (env.AUTH_JSON_URL) {
  println "Get role authorizations from URL " + env.AUTHZ_JSON_URL
  URL jsonUrl = new URL(env.AUTHZ_JSON_URL);
  access = new JsonSlurper().parse(jsonUrl);
}
else {
  println "Warning! Neither env.AUTHZ_JSON_FILE nor env.AUTHZ_JSON_URL specified!"
  println "Granting anonymous admin access"
}

/**
  * ===================================
  *
  *           Permissions
  *
  * ===================================
  */

// TODO: drive these from a config file
def adminPermissions = [
"hudson.model.Hudson.Administer",
"hudson.model.Hudson.Read"
]

def readPermissions = [
"hudson.model.Hudson.Read",
"hudson.model.Item.Discover",
"hudson.model.Item.Read"
]

def buildPermissions = [
"hudson.model.Hudson.Read",
"hudson.model.Item.Build",
"hudson.model.Item.Cancel",
"hudson.model.Item.Read",
"hudson.model.Run.Replay"
]

def roleBasedAuthenticationStrategy = new RoleBasedAuthorizationStrategy()
Jenkins.instance.setAuthorizationStrategy(roleBasedAuthenticationStrategy)

Constructor[] constrs = Role.class.getConstructors();
for (Constructor<?> c : constrs) {
  c.setAccessible(true);
}

// Make the method assignRole accessible
Method assignRoleMethod = RoleBasedAuthorizationStrategy.class.getDeclaredMethod("assignRole", RoleType.class, Role.class, String.class);
assignRoleMethod.setAccessible(true);
println("HACK! changing visibility of RoleBasedAuthorizationStrategy.assignRole")

/**
  *           Permissions
  */

Set<Permission> adminPermissionSet = new HashSet<Permission>();
adminPermissions.each { p ->
  def permission = Permission.fromId(p);
  if (permission != null) {
    adminPermissionSet.add(permission);
  } else {
    println(p + " is not a valid permission ID (ignoring)")
  }
}

Set<Permission> buildPermissionSet = new HashSet<Permission>();
buildPermissions.each { p ->
  def permission = Permission.fromId(p);
  if (permission != null) {
    buildPermissionSet.add(permission);
  } else {
    println(p + " is not a valid permission ID (ignoring)")
  }
}

Set<Permission> readPermissionSet = new HashSet<Permission>();
readPermissions.each { p ->
  def permission = Permission.fromId(p);
  if (permission != null) {
    readPermissionSet.add(permission);
  } else {
    println(p + " is not a valid permission ID (ignoring)")
  }
}

/**
  *      Permissions -> Roles
  */

// admins
Role adminRole = new Role(globalRoleAdmin, adminPermissionSet);
roleBasedAuthenticationStrategy.addRole(RoleType.Global, adminRole);

// builders
Role buildersRole = new Role(globalBuildRole, buildPermissionSet);
roleBasedAuthenticationStrategy.addRole(RoleType.Global, buildersRole);

// anonymous read
Role readRole = new Role(globalRoleRead, readPermissionSet);
roleBasedAuthenticationStrategy.addRole(RoleType.Global, readRole);

/**
  *      Roles -> Groups/Users
  */

access.admins.each { l ->
  println("Granting admin to " + l)
  roleBasedAuthenticationStrategy.assignRole(RoleType.Global, adminRole, l);
}

access.builders.each { l ->
  println("Granting builder to " + l)
  roleBasedAuthenticationStrategy.assignRole(RoleType.Global, buildersRole, l);
}

access.readers.each { l ->
  println("Granting read to " + l)
  roleBasedAuthenticationStrategy.assignRole(RoleType.Global, readRole, l);
}

Jenkins.instance.save()