+++
title = "AMQP 1.0 Messaging Network Configuration"
weight = 345
+++

The *Qpid Dispatch Router*, together with the *Apache Artemis* message broker, serves as the default *AMQP 1.0
Messaging Network* that is used in Hono's example deployment as described in the
[Deployment Guides]({{< relref "deployment" >}}).
<!--more-->

The Dispatch Router component exposes service endpoints implementing the *north bound* part of Hono's
[Telemetry]({{< relref "/api/telemetry" >}}), [Event]({{< relref "api/event" >}}) and
[Command & Control]({{< relref "/api/command-and-control" >}}) APIs which are used by applications to interact
with devices.


## Dispatch Router Configuration

The Dispatch Router is part of the [Apache Qpid project](https://qpid.apache.org). Hono uses the Dispatch Router by
means of the [EnMasse](https://enmasseproject.github.io) project's
[Dispatch Router Docker image](https://quay.io/repository/enmasse/qdrouterd-base) created from the Qpid project
source code.

The Dispatch Router can be configured by means of configuration files.
The [Eclipse IoT Packages](https://www.eclipse.org/packages/) project hosts an
[example configuration](https://github.com/eclipse/packages/blob/master/charts/hono/config/router/qdrouterd.json).
Please refer to the [Dispatch Router documentation](https://qpid.apache.org/components/dispatch-router/index.html)
for details regarding the configuration file format and options.

## Artemis Broker Configuration

The Artemis Broker is part of the [Apache ActiveMQ project](https://activemq.apache.org). Hono uses Artemis by means
of the [EnMasse](https://enmasseproject.github.io) project's
[Artemis Docker image](https://hub.docker.com/r/enmasseproject/activemq-artemis) created from the Artemis project
source code.

The Artemis Broker can be configured by means of configuration files.
The [Eclipse IoT Packages](https://www.eclipse.org/packages/) project hosts an
[example configuration](https://github.com/eclipse/packages/blob/master/charts/hono/config/artemis/broker.xml).
Please refer to the [Artemis documentation](https://activemq.apache.org/components/artemis/documentation/) for details
regarding the configuration file format and options.
