from emmett import App, abort, request, response
from emmett.tools import service


app = App(__name__)


@app.route("/status", methods="get")
async def status():
    response.status = 204
    return ""


@app.route("/search/find", methods="post")
@service.json
async def find():
    search = await request.body_params
    for list_index, candidate in enumerate(search.haystack):
        string_index = candidate.find(search.needle)
        if string_index >= 0:
            return {"listIndex": list_index, "stringIndex": string_index}
    abort(404)
