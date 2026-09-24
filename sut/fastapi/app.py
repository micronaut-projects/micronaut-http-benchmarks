from fastapi import FastAPI, Response
from pydantic import BaseModel


app = FastAPI()


class SearchRequest(BaseModel):
    haystack: list[str]
    needle: str


class SearchResult(BaseModel):
    listIndex: int
    stringIndex: int


@app.get("/status")
async def status() -> Response:
    return Response(status_code=204)


@app.post("/search/find", response_model=SearchResult)
async def find(search: SearchRequest) -> SearchResult | Response:
    for list_index, candidate in enumerate(search.haystack):
        string_index = candidate.find(search.needle)
        if string_index >= 0:
            return SearchResult(listIndex=list_index, stringIndex=string_index)
    return Response(status_code=404)
