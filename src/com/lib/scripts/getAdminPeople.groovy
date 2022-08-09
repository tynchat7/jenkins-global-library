import hudson.model.Hudson
import com.michelin.cio.hudson.plugins.rolestrategy.RoleBasedAuthorizationStrategy    

def trigerUser = 'fsadykov'
def environment = "prod" 
def commonFunction = new com.lib.scripts.commonFunction()

if (commonFunction.isAdmin(trigerUser)) {
    println("You are allowed to do prod deployments!!")
} else {
    println("You are not allowed to do prod deployments!!")
}
