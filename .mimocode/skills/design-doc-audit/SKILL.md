---
description: Audit design documents against implementation to find feature gaps
---

# Design Document Audit Skill

Systematic workflow for checking design documents against actual implementation to identify missing features and gaps.

## When to Use

- Before starting a new feature sprint
- After completing a major feature batch
- When user asks "what's not implemented yet?"
- During project health checks

## Step 1: Read Design Document

```bash
# Read the main design document
cat /home/cooper/raindrop/doc/DESIGN.md
```

Key sections to focus on:
- §11: Terminal backend (JediTermFX only)
- §6.0: Quick Connect in main ToolBar
- §4.3: SFTP batch download
- Module descriptions and feature lists

## Step 2: Map Implementation

Check key source files against design:

```bash
# List all Java source files
find /home/cooper/raindrop/src/main/java -name "*.java" | sort

# Check specific modules
ls /home/cooper/raindrop/src/main/java/com/raindrop/core/
ls /home/cooper/raindrop/src/main/java/com/raindrop/terminal/
ls /home/cooper/raindrop/src/main/java/com/raindrop/credential/
ls /home/cooper/raindrop/src/main/java/com/raindrop/ui/
```

## Step 3: Identify Gaps

Compare design features against implementation:

| Design Feature | Implementation Status | Gap |
|----------------|----------------------|-----|
| SSH connection | ✅ Implemented | - |
| SFTP browser | ✅ Implemented | - |
| Terminal emulation | ✅ JediTermFX only | - |
| Credential management | ✅ Implemented | - |
| Quick Connect | ✅ Embedded HBox | - |
| Port forwarding | ❌ Not implemented | New feature |
| Session logging | ❌ Not implemented | New feature |

## Step 4: Prioritize Gaps

Categorize gaps by type:

1. **Core Features**: Missing from design but essential
2. **Enhancements**: Nice-to-have improvements
3. **Tech Debt**: Implementation issues or bugs
4. **Security**: Missing security features

## Step 5: Report Findings

Present findings as numbered list for user selection:

```
Found 5 implementation gaps:

1. [Core] SSH port forwarding - Not implemented
2. [Core] Session logging/recording - Not implemented  
3. [Enhancement] Global search across connections - Not implemented
4. [Tech Debt] JediTermFX context menu i18n - Partially implemented
5. [Security] Host key verification UI - Not implemented

Which would you like to implement? (Enter numbers, e.g., "1,3")
```

## Step 6: Create Implementation Plan

For selected gaps, create detailed implementation plan:

1. Files to touch
2. Dependencies
3. Testing requirements
4. Estimated effort

## Example Session

```bash
# User: "检查一下设计文档，看看还有什么功能没实现"
# Agent: Read DESIGN.md → Map implementation → Identify gaps → Report
```

## Notes

- Design document may be outdated - verify against actual code
- Some features may be intentionally deferred
- Check CLAUDE.md for current constraints and rules
- Use CodeGraph for efficient code navigation when available
