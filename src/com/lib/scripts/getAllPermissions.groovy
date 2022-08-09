import jenkins.model.*
import hudson.security.*


def permissions = Permission.getAll()

permissions.forEach() {
    println(it.getId())
}