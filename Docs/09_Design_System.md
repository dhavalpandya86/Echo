# 09 --- Design System Specification

**Product:** Echo\
**Version:** 1.0 (Living Document)

# 1. Purpose

This document defines the visual language, interaction principles, and
reusable UI components that create a consistent experience across Echo.

# 2. Design Philosophy

Echo should feel: - Calm - Private - Premium - Timeless - Human -
Minimal

Technology should disappear behind the experience of preserving
memories.

# 3. Brand Foundations

## Colors

Primary - Echo Electric Violet - Echo Deep Indigo

Neutral - Echo Soft Paper - Echo Midnight - Echo Ghost Gray

Functional - Success: Echo Neon Mint - Warning: Echo Warning Coral -
Error: Material error palette

Rules: - Use semantic colors, not hardcoded values. - Maintain WCAG AA
contrast.

# 4. Typography

Primary font: Manrope

Hierarchy: - Display - Headline - Title - Body - Label

Guidelines: - Generous whitespace - Large recording timer - Maximum
readability - Dynamic type support

# 5. Spacing Scale

4dp 8dp 12dp 16dp 24dp 32dp 48dp 64dp

Use an 8-point grid wherever possible.

# 6. Shape System

Small: 16dp Medium: 24dp Large: 32dp FAB: Circular

Cards use soft rounded corners with subtle elevation.

# 7. Motion Principles

Motion communicates state.

Use: - Fade - Scale - Crossfade - Gentle slide - Subtle breathing

Avoid: - Excessive bounce - Flashing animations

Respect Android "Reduce Motion" settings.

# 8. Component Library

Core components: - EchoButton - EchoRecordFAB - EchoCard -
EchoTimelineCard - EchoCollectionCard - EchoSearchBar - EchoTopBar -
EchoSectionHeader - EchoEmptyState - PlaybackControls - RealWaveform -
BreathingRecordButton

Components must be reusable and stateless where possible.

# 9. Screen Standards

Home: - Greeting - Journal stats - Recent memories

Record: - Full-screen capture - Waveform - Timer - Primary recording
controls

Timeline: - Sticky date headers - Memory cards

Search: - Search bar - Filter chips

Settings: - Grouped preferences - Privacy-first messaging

# 10. Accessibility

-   Minimum touch target: 48dp
-   Screen reader labels
-   Keyboard navigation where applicable
-   Dynamic font scaling
-   High contrast support
-   Reduced motion support

# 11. Responsive Design

Support: - Phones - Foldables - Tablets - Landscape

Layouts adapt without changing functionality.

# 12. Design Tokens

Centralize: - Colors - Typography - Shapes - Elevation - Spacing -
Animation durations - Corner radii

No hardcoded UI values outside the theme.

# 13. Future Evolution

The design system should scale to: - Desktop - Web - Wear OS

while maintaining a unified Echo identity.
