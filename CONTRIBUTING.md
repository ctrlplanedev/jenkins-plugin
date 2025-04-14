# Contributing to the Ctrlplane Jenkins Plugin

Thank you for considering contributing to the Ctrlplane Jenkins Plugin!

We welcome pull requests! Please follow these steps:

1.  **Fork the Repository:** Create your own fork of the [ctrlplanedev/jenkins-plugin](https://github.com/ctrlplanedev/jenkins-plugin) repository.
2.  **Create a Branch:** Create a new branch in your fork for your changes (e.g., `git checkout -b feature/my-new-feature` or `git checkout -b fix/bug-description`).
3.  **Make Changes:** Implement your fix or feature.
    *   Adhere to the existing code style. Consider using `mvn spotless:apply verify` to format your code.
    *   Add unit tests for new functionality or bug fixes if applicable.
4.  **Test:** Build the plugin (`mvn clean package`) and test your changes in a local Jenkins instance if possible.
5.  **Commit:** Commit your changes with clear and concise commit messages.
6.  **Push:** Push your branch to your fork (`git push origin feature/my-new-feature`).
7.  **Open a Pull Request:** Go to the original repository and open a pull request from your branch to the `main` branch of `ctrlplanedev/jenkins-plugin`.
    *   Provide a clear title and description for your pull request, explaining the changes and referencing any related issues (e.g., "Fixes #123").

## Code Style

This project uses [Spotless Maven Plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven) to enforce code style. Please run `mvn spotless:apply` before committing to format your code automatically.

## Questions?

If you have questions about contributing, feel free to open an issue.

Thank you for your contributions!
