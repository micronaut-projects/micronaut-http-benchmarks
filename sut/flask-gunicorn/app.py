from flask import Flask, Response, request


app = Flask(__name__)
app.json.compact = True


@app.get("/status")
def status() -> Response:
    return Response(status=204)


@app.post("/search/find")
def find() -> tuple[Response, int] | Response:
    search = request.get_json()
    for list_index, candidate in enumerate(search["haystack"]):
        string_index = candidate.find(search["needle"])
        if string_index >= 0:
            return app.json.response(listIndex=list_index, stringIndex=string_index)
    return Response(status=404)
