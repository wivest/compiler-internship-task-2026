# MiniKotlin to Java CPS Compiler

This is an internship assignment for implementing a CPS-style (Continuation-Passing Style) compiler from MiniKotlin (a
subset of Kotlin) to Java.

## Overview

The goal is to implement a compiler that translates MiniKotlin source code into Java, where all functions are expressed
using continuation-passing style.

## Project Structure

- `samples/` - Example MiniKotlin programs
- `src/main/antlr/MiniKotlin.g4` - Grammar definition for MiniKotlin
- `src/main/kotlin/compiler/` - Compiler implementation
- `src/test/` - Testing framework
- `stdlib/` - Standard library with `Prelude` class containing CPS function examples

## Task

Implement the `MiniKotlinCompiler` to translate MiniKotlin to Java such that:

1. All functions use continuation-passing style
2. The semantics of operators follows Kotlin

See `Prelude` in the stdlib for examples of how CPS functions should look.

## Advanced example

After implementation we can compile complex structures.

Suppose that the MiniKotlin code looks like this:

```kotlin
fun mul(num: Int): Int {
    var n: Int = num
    var i: Int = 0
    while (i < 4) {
        if (i == 3) {
            return n
        }
        var j: Int = 0
        while (j < 3) {
            j = j + 1
            n = n + 1
        }
        i = i + 1
    }
    return n
}

fun main(): Unit {
    println(mul(0))
}
```

Then the supposed implementation can look like this (formatted):

```java
import java.util.Objects;
import java.util.Comparator;

public class MiniProgram {
    public static void mul(Integer num, Continuation<Integer> __continuation) {
        final Integer[] n = {num};
        final Integer[] i = {0};
        Runnable while1 = new Runnable() {
            @Override
            public void run() {
                if (Objects.compare(i[0], 4, Comparator.naturalOrder()) < 0) {
                    if (Objects.equals(i[0], 3)) {
                        __continuation.accept(n[0]);
                        return;
                    } else {
                        final Integer[] j = {0};
                        Runnable while0 = new Runnable() {
                            @Override
                            public void run() {
                                if (Objects.compare(j[0], 3, Comparator.naturalOrder()) < 0) {
                                    j[0] = j[0] + 1;
                                    n[0] = n[0] + 1;
                                    this.run();
                                } else {
                                    i[0] = i[0] + 1;
                                }

                            }
                        };
                        while0.run();
                    }
                    this.run();
                } else {
                    __continuation.accept(n[0]);
                    return;
                }

            }
        };
        while1.run();
    }

    public static void main(String[] args) {
        mul(0, (arg0) -> {
            Prelude.println(arg0, (arg1) -> {

            });
        });
    }

}
```

## Building and Running

```bash
# Build the project
./gradlew build

# Run with default example
./gradlew run

# Run with a specific file
./gradlew run --args="samples/example.mini"

# Run tests
./gradlew test
```

## Evaluation

The task will be tested on a hidden set of tests.
