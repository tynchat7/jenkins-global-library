#!/usr/bin/env groovy

package com.lib


def sendMessage(String type, String status, String channel, String message = null) {
  try {
    String color        = ""
    String slackUrl     = "https://hooks.slack.com/services/lkhkhkhk"
    String slackToken   = "slack-token"

    if (!channel.contains("#")) {
      channel = "#" + channel
    }

    switch(status) {
      case "SUCCESS":
        color = "#00ff72"
        if (message == null ) {
          message = """
          Jenkins Job was successfully built.
          email: fuchicorpsolution@gmail.com
          SUCCESS: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})
          """.stripIndent()
        }
        break

      case "FAILURE":
        color = "#FF0000"
        if (message == null ) {
          message = """
          Jenkins build is breaking for some reason. Please go to job and take actions.
          email: fuchicorpsolution@gmail.com
          FAILED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL})")
          """.stripIndent()   }
        break

      case "STARED":
        color = "#FFFF00"
        if (message == null ) {
          message = """
          The Jenkins job was stared
          email: fuchicorpsolution@gmail.com
          STARTED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}).
          """.stripIndent()   }
        break

      case "PLANED":
        color = "#00ffbf"
        if (message == null ) {
          message = """
          ##### Terraform Plan (Check) the Changes #####
          email: fuchicorpsolution@gmail.com
          PLANED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}).
          """.stripIndent()   }
        break

      case "APPLIED":
        color = "#ffd000"
        if (message == null ) {
          message = """
          ##### Terraform Applying the Changes #####
          email: fuchicorpsolution@gmail.com
          APPLIED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}).
          """.stripIndent()   }
        break

      case "DESTROYED":
        color = "#ff0000"
        if (message == null ) {
          message = """
          ##### Terraform Destroing #####
          email: fuchicorpsolution@gmail.com
          DESTROYED: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}).
          """.stripIndent()   }
        break



      default:
        color = "#00f6ff"
        if (message == null ) {
          message = """
          The Jenkins job was ${status} and was successfully builded.
          email: fuchicorpsolution@gmail.com
          ${status}: Job '${env.JOB_NAME} [${env.BUILD_NUMBER}]' (${env.BUILD_URL}).
          """.stripIndent()   }
        break
    }

    switch(type) {
      case "slack":
        slackSend(channel: channel, color: color, baseUrl: slackUrl, tokenCredentialId: slackToken, message: message)
        break
      default:
        println("No default notification system please use slack")
        break
      }
    } catch(e) {
      println("The Jenkins server was not able to send message")
      println(e.getMessage())
    }
}


return this
