def params = [:]
params['terraform_destroy'] = true
params['terraform_apply'] = true

def terraformTrrigger(params) {
    if ( params.terraform_apply && ! params.terraform_destroy ) {
        println "Applying the changes to the infrastucture"
    } else if ( params.terraform_destroy && ! params.terraform_apply) {
        println "Destroying everything"
    } else if ( ! params.terraform_destroy && ! params.terraform_apply) {
        println "Running the terraform plan"
    } else if ( params.terraform_destroy && params.terraform_apply) {
        println "Sorry I ca't do apply and destroy"
    }
}

terraformTrrigger(params, )



