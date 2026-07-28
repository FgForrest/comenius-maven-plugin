---
title: Fences and the phantom headings they hide
---

Prose before the first fence.

```shell
# this is a shell comment, not a heading
docker run --name evitadb -i --rm evitadb/evitadb:latest
   # an indented comment, still not a heading
```

## A real heading

A fence may quote Markdown, including something that looks like a tag:

````markdown
### not a heading either

```java
List<SealedEntity> entities = session.queryList(query, SealedEntity.class);
```

<Note type="info">this Note is quoted, not opened</Note>
````

Tilde fences behave the same way:

~~~
### also quoted
<LS to="j">
~~~

A fence closes only on a run at least as long as the one that opened it:

`````text
```
this inner run is too short to close anything
```
`````

Inline code such as `<Note>` or `` `nested` `` is masked too.

## Last heading

Closing prose.
