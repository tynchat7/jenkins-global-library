import jenkins.model.*
import hudson.security.*

def instance = Jenkins.getInstance()

def isAdmin(username) {
    // Function is responsible to return true if the user is admin or false 
    def instance = Jenkins.getInstance()
    def user = User.get(username)
  	return instance.getAuthorizationStrategy().getACL(user).hasPermission(user.impersonate(), hudson.model.Hudson.ADMINISTER)
}
println("is user adnankursun has admin access: ${isAdmin('adnankursun')}")
println("is user ikambarov has admin access: ${isAdmin('ikambarov')}")
println("is user fsadykov has admin access: ${isAdmin('fsadykov')}")