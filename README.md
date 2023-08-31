# MII Process Data Transfer

In this repository you will find the process to transfer data-sets of an approved data usage project from a DIC to a DMS.

## Development
Branching follows the git-flow model, for the latest development version see branch [develop](https://github.com/medizininformatik-initiative/mii-process-data-transfer/tree/develop).

## Build

Prerequisite: Java 17, Maven >= 3.6

To use this repository in your code, add the Github Package Registry server to your Maven `.m2/settings.xml`. Instructions on how to generate the `USERNAME` and `TOKEN` can be found in the GitHub documentation [Managing your personal access tokens](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens). The token needs at least the `read:packages` scope.

```xml
<servers>
    <server>
        <id>github-mii</id>
        <username>USERNAME</username>
        <password>TOKEN</password>
    </server>
</servers>
```

## License
All code is published under the [Apache-2.0 License](LICENSE).
