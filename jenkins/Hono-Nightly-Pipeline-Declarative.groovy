/*******************************************************************************
 * Copyright (c) 2016, 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

/**
 * Jenkins pipeline script for nightly build (every night between 2 and 3 AM) of Hono master.
 *
 */

pipeline {
  agent {
    kubernetes {
      label 'my-agent-pod'
      yaml """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: maven
    image: "maven:3.8.4-eclipse-temurin-17"
    tty: true
    command:
    - cat
    volumeMounts:
    - mountPath: /home/jenkins
      name: "jenkins-home"
    - mountPath: /home/jenkins/.ssh
      name: "volume-known-hosts"
    - name: "settings-xml"
      mountPath: /home/jenkins/.m2/settings.xml
      subPath: settings.xml
      readOnly: true
    - name: "settings-security-xml"
      mountPath: /home/jenkins/.m2/settings-security.xml
      subPath: settings-security.xml
      readOnly: true
    - name: "m2-repo"
      mountPath: /home/jenkins/.m2/repository
    - name: "toolchains-xml"
      mountPath: /home/jenkins/.m2/toolchains.xml
      subPath: toolchains.xml
      readOnly: true
    env:
    - name: "HOME"
      value: "/home/jenkins"
    resources:
      limits:
        memory: "6Gi"
        cpu: "2"
      requests:
        memory: "6Gi"
        cpu: "2"
  volumes:
  - name: "jenkins-home"
    emptyDir: {}
  - name: "m2-repo"
    emptyDir: {}
  - configMap:
      name: known-hosts
    name: "volume-known-hosts"
  - name: "settings-xml"
    secret:
      secretName: m2-secret-dir
      items:
      - key: settings.xml
        path: settings.xml
  - name: "settings-security-xml"
    secret:
      secretName: m2-secret-dir
      items:
      - key: settings-security.xml
        path: settings-security.xml
  - name: "toolchains-xml"
    configMap:
      name: m2-dir
      items:
      - key: toolchains.xml
        path: toolchains.xml
"""
    }
  }

  options {
    buildDiscarder(logRotator(numToKeepStr: '3'))
    disableConcurrentBuilds()
    timeout(time: 45, unit: 'MINUTES')
  }

  triggers {
      cron('TZ=Europe/Berlin\n# every night between 2 and 3 AM\nH 2 * * *')
  }

  stages {

    stage('Build and deploy to Eclipse Repo') {
      steps {
        container('maven') {
          echo "checking out branch [master] ..."
          checkout([$class           : 'GitSCM',
                    branches         : [[name: "refs/heads/master"]],
                    userRemoteConfigs: [[url: 'https://github.com/eclipse/hono.git']]])

          echo "building and deploying nightly artifacts ..."
          sh 'mvn deploy -DnoDocker -DcreateJavadoc=true -DenableEclipseJarSigner=true'

          echo "recording JUnit test results ..."
          junit '**/surefire-reports/*.xml'

          echo "publishing JavaDoc ..."
          sh 'mvn package javadoc:aggregate -DskipTests -DnoDocker'
          step([$class: 'JavadocArchiver', javadocDir: 'target/site/apidocs'])

          echo "archiving Command Line Client ..."
          step([$class: 'ArtifactArchiver', artifacts: "cli/target/hono-cli-*-exec.jar"])
        }
      }

    }
  }

  post {
    fixed {
      step([$class                  : 'Mailer',
            notifyEveryUnstableBuild: true,
            recipients              : 'hono-dev@eclipse.org',
            sendToIndividuals       : false])
    }
    failure {
      step([$class                  : 'Mailer',
            notifyEveryUnstableBuild: true,
            recipients              : 'hono-dev@eclipse.org',
            sendToIndividuals       : false])
    }
  }
}
