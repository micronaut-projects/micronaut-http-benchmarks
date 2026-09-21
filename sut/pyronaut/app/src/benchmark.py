from dataclasses import dataclass
from typing import Annotated

from micronaut.http.annotation import Body, Get, Post
from micronaut.serde.annotation import Serdeable


@Serdeable
@dataclass(frozen=True, slots=True)
class Status:
    pass


@Serdeable
@dataclass(frozen=True, slots=True)
class SearchRequest:
    haystack: list[str]
    needle: str


@Serdeable
@dataclass(frozen=True, slots=True)
class SearchResult:
    listIndex: int
    stringIndex: int


@Get("/status")
def status() -> Status:
    return Status()


@Post("/search/find")
def find(request: Annotated[SearchRequest, Body]) -> SearchResult | None:
    for list_index, candidate in enumerate(request.haystack):
        string_index = candidate.find(request.needle)
        if string_index >= 0:
            return SearchResult(list_index, string_index)
    return None
