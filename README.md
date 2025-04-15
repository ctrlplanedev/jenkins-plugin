# ctrlplane-plugin

## Introduction

This plugin integrates Jenkins with Ctrlplane by acting as a Job Agent.
It allows Ctrlplane to trigger specific Jenkins pipeline jobs as part of a Deployment workflow.
The plugin polls Ctrlplane for pending jobs assigned to it and injects job context (like the Ctrlplane Job ID) into the triggered Jenkins pipeline.

## Getting started

For detailed installation, configuration, and usage instructions, please refer to the official documentation:

[**Ctrlplane Jenkins Integration Documentation**](https://docs.ctrlplane.dev/integrations/saas/jenkins)

## Issues

Report issues and enhancements on the [GitHub Issues page](https://github.com/ctrlplanedev/jenkins-plugin/issues).

## Contributing

Refer to our [contribution guidelines](https://github.com/jenkinsci/.github/blob/master/CONTRIBUTING.md)

## LICENSE

Licensed under MIT, see [LICENSE](LICENSE)
