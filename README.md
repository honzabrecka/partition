# partition

A tool that helps with running tasks in parallel across multiple workers on [CircleCI](https://circleci.com) the ~ideal way.

## Motivation

Suppose you have 5 tests (pair of name and execution time) _{A: 12, B: 2, C: 8, D: 2, E: 5}_ and 2 workers _{X, Y}_ and you want to preserve the overall execution time as small as possible. The naïve solution won't work - in this case you may end up with subsets _{A: 12, C: 8, E: 5}_ and _{B: 2, D: 2}_. These subsets lead to a big difference between total execution time of each worker - _25 (86.2%)_ vs. _4 (13.8%)_. The reason is that the exection times of each test are completely ignored.

Here comes the partition, which solves this problem almost ideally: Subsets produced by partition are _{A: 12, B: 2}_ and _{C: 8, D: 2, E: 5}_ - _14 (48.3%)_ vs. _15 (51.7%)_.

## How it works?

 1. **Reads saved output from the previous successfull run.** Each test in the output has to contain filename and execution time. To obtain the output from the previous run you need to have [an access token to build artifacts](https://circleci.com/docs/build-artifacts/).
 1. **Loads all files in current test suite(s).** Tests may be added and/or deleted accross runs.
 1. **Applies solution of minimal difference partition problem to the set of execution times.**

## Usage

```console
$ java -jar partition.jar --access-token <ACCESS_TOKEN> <PATH_TO_TESTS>
```

## Modes

Mode can be set by `-m` (`--mode`) option.

 - **delete**: By default, partition operates in `delete` mode, which means that only files contained in bucket are preserved; other files are deleted.
 - **copy** - Second mode called `copy` copies files contained in bucket to newly created directory; original directory is left untouched.

## Example of circle.yml

```yml
dependencies:
  post:
   - java -jar partition.jar --access-token <ACCESS_TOKEN> test/features:
       {parallel: true}

test:
  override:
    - set -o pipefail; nightwatch -g test/features | tee nightwatch_output:
        {parallel: true}

general:
  artifacts:
    - nightwatch_output
```

## Test

[![CircleCI](https://circleci.com/gh/blueberryapps/partition.svg?style=svg)](https://circleci.com/gh/blueberryapps/partition)

```console
$ lein test partition.core
```
