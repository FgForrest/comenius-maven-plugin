---
title: Inline tags, containers and generics in prose
---

## Inline markup inside a sentence

The <Term>entity</Term> is stored in a <Term>collection</Term>, and the class that
does it is <SourceClass>evita_engine/src/main/java/io/evitadb/core/Evita.java</SourceClass>
which you should not translate.

A self closing tag such as <MDInclude src="fragment.md"/> stands on its own.

## A container with cosmetic indentation

<Table>
    <Thead>
        <Tr>
            <Th>Variable</Th>
            <Th>Description</Th>
        </Tr>
    </Thead>
    <Tbody>
        <Tr>
            <Td>**`EVITA_STORAGE_DIR`**</Td>
            <Td>Path to the storage directory, default: `/evita/data`</Td>
        </Tr>
        <Tr>
            <Td>**`EVITA_JAVA_OPTS`**</Td>
            <Td>Java command line arguments,
            wrapped over several lines,
            default: none (empty string)</Td>
        </Tr>
    </Tbody>
</Table>

## Generics that only look like markup

Prose mentioning `List<SealedEntity>` and `Map<String, Integer>` inside code spans
must survive untouched, because a code span is masked before tags are scanned.

## Attribute values that contain the delimiter

<Note type="info" title="Comparing a > b is fine here">

An unquoted `>` inside a quoted attribute value must not truncate the tag.

</Note>

Closing prose.
