# partition

A tool solving partition problem.

## Usage

```console
$ lein run

  -t, --access-token ACCESS_TOKEN                          Access Token
  -u, --user USER                                          User
  -p, --project PROJECT                                    Project
  -b, --branch BRANCH              master                  Branch
  -r, --regexp REGEXP              ^.+?nightwatch_output$  Artifact url pattern
  -c, --count COUNT                                        Count of workers
```

## Test

[![CircleCI](https://circleci.com/gh/blueberryapps/partition.svg?style=svg)](https://circleci.com/gh/blueberryapps/partition)

```console
$ lein test partition.core
```
