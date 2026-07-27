---
description: Run gradle build/test with correct JAVA_HOME setup for Raindrop project
---

# Gradle Build Command

Run the standard gradle build/test command for the Raindrop project with the correct JAVA_HOME environment.

## Usage

```bash
# Compile only (fast check)
$ARGUMENTS compileJava

# Compile and run tests
$ARGUMENTS compileJava test

# Full build (skip tests)
$ARGUMENTS build -x test

# Run the application
$ARGUMENTS run
```

## Default Command

If no arguments provided, run compile + test:

```bash
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10 && /home/cooper/MySoft/gradle-8.5/bin/gradle compileJava test 2>&1
```

## Environment Setup

The Raindrop project requires:
- **JAVA_HOME**: `/home/cooper/MySoft/jdk-21.0.11+10` (JDK 21 LTS)
- **Gradle**: `/home/cooper/MySoft/gradle-8.5/bin/gradle`

## Common Variations

```bash
# Compile only
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10 && /home/cooper/MySoft/gradle-8.5/bin/gradle compileJava 2>&1 | tail -10

# Run tests only
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10 && /home/cooper/MySoft/gradle-8.5/bin/gradle test 2>&1 | tail -10

# Full build
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10 && /home/cooper/MySoft/gradle-8.5/bin/gradle build -x test 2>&1 | tail -50

# Run application
export JAVA_HOME=/home/cooper/MySoft/jdk-21.0.11+10 && /home/cooper/MySoft/gradle-8.5/bin/gradle run 2>&1 | tail -20
```

## Notes

- Tests use a throwaway SQLite database under `build/test-tmp/`
- Never touch `~/.raindrop/raindrop.db` during tests
- Use `| tail -N` to limit output for quick verification
