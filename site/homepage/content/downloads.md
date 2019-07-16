+++
title = "Downloads"
menu = "main"
linkTitle = "Download"
weight = 150
+++

## Binaries

Eclipse Hono's service components are provided by means of pre-built Docker images available from
[Docker Hub](https://hub.docker.com/u/eclipse/). These container images can be deployed to popular
container orchestration platforms like Kubernetes and OpenShift.

The release archive contains all files necessary to deploy Hono to a Kubernetes cluster using the
[Helm package manager](https://helm.sh).

* [Eclipse Hono 0.9 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-deploy-0.9.tar.gz)

Download and extract the archive and follow the [deployment guide]({{% doclink "/deployment/helm-based-deployment/" %}}).


A Java based command line client for consuming telemetry data and events from Hono is available for download as well:

* [Eclipse Hono 0.9 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-0.9-exec.jar)

The client requires a Java 11 runtime environment to run.

Please refer to the [Admin Guide]({{% doclink "/admin-guide/hono-client-configuration/" %}}) for details regarding the command line options that the client supports.

### Latest Milestone

The newest features and bug fixes are available in *milestones* that are published every 4 -6 weeks. Please note that these milestones represent the latest
*state of development* which also means that APIs or features may change from one milestone to the other.
Installation works in the same way as for the latest stable release.

* [Eclipse Hono 1.0-M5 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-1.0-M5-chart.tar.gz)
* [Eclipse Hono 1.0-M5 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-1.0-M5-exec.jar)
* [Eclipse Hono 1.0-M4 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-1.0-M4-deploy.tar.gz)
* [Eclipse Hono 1.0-M4 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-1.0-M4-exec.jar)
* [Eclipse Hono 1.0-M3 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-deploy-1.0-M3.tar.gz)
* [Eclipse Hono 1.0-M3 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-1.0-M3-exec.jar)
* [Eclipse Hono 1.0-M2 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-deploy-1.0-M2.tar.gz)
* [Eclipse Hono 1.0-M2 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-1.0-M2-exec.jar)
* [Eclipse Hono 1.0-M1 Archive](https://www.eclipse.org/downloads/download.php?file=/hono/eclipse-hono-deploy-1.0-M1.tar.gz)
* [Eclipse Hono 1.0-M1 Command Line Client](https://www.eclipse.org/downloads/download.php?file=/hono/hono-cli-1.0-M1-exec.jar)

## Source Code

The source code can be cloned (using [Git](https://git-scm.com/)) or downloaded from the [Eclipse Hono GitHub repository]
(https://github.com/eclipse/hono). The [Building from Source]({{% doclink "/dev-guide/building_hono/" %}})
guide provides instructions on how to build Hono locally.

## Release Notes

For a list of the changes in this release, take a look at the [release notes]({{< relref "release-notes" >}}).
