# SCP publisher - deprecated

This plugin is **deprecated** due to open security vulnerabilities and readily available replacements provided with currently supported operating systems.

There are two open security vulnerabilities.
The [plain text credentials storage](https://www.jenkins.io/security/advisory/2017-10-23/#scp-publisher-plugin-stores-credentials-unencrypted-on-disk-round-trips-in-unencrypted-form) security vulnerability was published in October 2017.
The [CSRF vulnerability and missing permission check](https://www.jenkins.io/security/advisory/2022-02-15/#SECURITY-2323) security vulnerability was published in February 2022.

The `scp` command is available on all the operating systems that Jenkins supports.
Unix variants include `scp` with the OpenSSH commands.
Windows includes `scp` with their port of OpenSSH.

The plugin was last released in January 2011.
The last commit with any change to the plugin source code was in January 2014.

The last successful build on ci.jenkins.io was in September 2020.
The plugin does not build currently.
