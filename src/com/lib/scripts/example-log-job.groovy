def google_bucket_name          = "fuchicorp-common-35"
def google_project_id           = "elevated-nuance-307722"
def google_domain_name          = "fuchicorp.com"
def jenkins_job_log             = ""

// Getting some envs <DOMAIN_NAME> <GOOGLE_BUCKET_NAME> <GOOGLE_PROJECT_ID>
node("master") {
    google_domain_name = sh(returnStdout: true, script: 'echo $DOMAIN_NAME').trim()
    google_bucket_name = sh(returnStdout: true, script: 'echo $GOOGLE_BUCKET_NAME').trim() 
    google_project_id = sh(returnStdout: true, script: 'echo $GOOGLE_PROJECT_ID').trim()
}

// Getting the builds log as <jenkins_job_log>
try {
    def baos = new ByteArrayOutputStream()
    currentBuild.rawBuild.getLogText().writeLogTo(0, baos)
    jenkins_job_log = baos.toString()
} catch(e) {
    println(e.getMessage())
}


def k8slabel = "jenkins-pipeline-${UUID.randomUUID().toString()}"
def slavePodTemplate = """
      metadata:
        labels:
          k8s-label: ${k8slabel}
        annotations:
          jenkinsjoblabel: ${env.JOB_NAME}-${env.BUILD_NUMBER}
      spec:
        affinity:
          podAntiAffinity:
            requiredDuringSchedulingIgnoredDuringExecution:
            - labelSelector:
                matchExpressions:
                - key: component
                  operator: In
                  values:
                  - jenkins-jenkins-master
              topologyKey: "kubernetes.io/hostname"
        containers:
        - name: docker
          image: docker:latest
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        - name: fuchicorptools
          image: fuchicorp/buildtools:latest
          imagePullPolicy: Always
          command:
          - cat
          tty: true
          volumeMounts:
            - mountPath: /var/run/docker.sock
              name: docker-sock
            - mountPath: /etc/secrets/service-account/
              name: google-service-account
        serviceAccountName: common-service-account
        securityContext:
          runAsUser: 0
          fsGroup: 0
        volumes:
          - name: google-service-account
            secret:
              secretName: google-service-account
          - name: docker-sock
            hostPath:
              path: /var/run/docker.sock
    """
    
    podTemplate(name: k8slabel, label: k8slabel, yaml: slavePodTemplate, showRawYaml: false) {
      node(k8slabel) {
          container("fuchicorptools") {
                stage("Upload Log") {
                    writeFile( [file: "${WORKSPACE}/example.log", text: "${jenkins_job_log}"] )
                    sh """
                    gcloud auth activate-service-account --key-file=/etc/secrets/service-account/credentials.json
                    gsutil cp ${WORKSPACE}/example.log  gs://${google_bucket_name}/log1.txt
                    """
                }
          }
      }
    }