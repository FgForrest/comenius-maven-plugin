---
title: Heading inside a tag
---

Intro prose that belongs to no heading at all.

<LS to="j">

## Java section

Some Java specific prose.

### Detail of the Java section

More detail, still inside the language switch.

</LS>

Prose that follows the closing tag and must **not** belong to the Java section,
because the tag scope ended before the section did.

## Next chapter

Body of the next chapter.

<Note type="info">

### Heading nested in a note

A note may carry its own outline without leaking into the document outline.

</Note>

Trailing prose after the note.
