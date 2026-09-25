import json

from django.http import HttpResponse, JsonResponse
from django.urls import path


def status(request):
    return HttpResponse(status=204)


def find(request):
    search = json.loads(request.body)
    for list_index, candidate in enumerate(search["haystack"]):
        string_index = candidate.find(search["needle"])
        if string_index >= 0:
            return JsonResponse({"listIndex": list_index, "stringIndex": string_index}, json_dumps_params={"separators": (",", ":")})
    return HttpResponse(status=404)


urlpatterns = [
    path("status", status),
    path("search/find", find),
]
