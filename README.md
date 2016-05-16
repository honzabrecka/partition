# partition

A tool solving partition problem you'll have when trying to run your tests in parallel.

## Usage

```console
$ lein run

    -a, --access-token ACCESS_TOKEN                     Access Token
    -u, --user USER                                     User
    -p, --project PROJECT                               Project
    -b, --branch BRANCH                                 Branch
    -r, --regexp REGEXP              nightwatch_[0-9]$  Artifact url pattern
    -c, --count COUNT                                   Count of workers
```

## Test

[![CircleCI](https://circleci.com/gh/honzabrecka/partition.svg?style=svg&circle-token=631ae11afa9d54f815793e072d27470912b4a61c)](https://circleci.com/gh/honzabrecka/partition)

```console
$ lein test partition.core
```
