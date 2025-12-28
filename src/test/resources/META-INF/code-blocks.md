---
title: Code Examples
author: Developer
---

This document contains various code blocks that should NOT be translated.

Fenced Java code block:

```java
public class Test {
    // This should NOT be translated
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

Fenced Python code block:

```python
def hello():
    # This should NOT be translated either
    print("world")
    return 42
```

Fenced shell code block:

```bash
#!/bin/bash
echo "Running script"
npm install
mvn clean install
```

Inline code like `System.out.println()` should also be preserved.

Code block without language specification:

```
This is a generic code block
with multiple lines
that should not be translated
```
