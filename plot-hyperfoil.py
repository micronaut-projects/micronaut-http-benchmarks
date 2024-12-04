#!/usr/bin/python3
import json
import math
import matplotlib.axes
import matplotlib.axis
import matplotlib.patches
import matplotlib.pyplot as plt
import matplotlib.scale
import numpy as np
import typing


def percentile_transform(values):
    return -np.log10(1 - values)


def percentile_transform_reverse(values):
    return 1 - np.float_power(10, -values)


assert percentile_transform(0) == 0
assert math.isclose(percentile_transform(0.9), 1)
assert math.isclose(percentile_transform(0.99), 2)
assert math.isclose(percentile_transform(0.999), 3)
assert percentile_transform_reverse(0) == 0
assert math.isclose(percentile_transform_reverse(1), 0.9)
assert math.isclose(percentile_transform_reverse(2), 0.99)
assert math.isclose(percentile_transform_reverse(3), 0.999)


def generate_percentile_ticks(limit):
    i = 0
    while True:
        percentile = percentile_transform_reverse(i)
        yield percentile
        if percentile >= limit:
            break
        i += 1



def plot_histogram(ax: matplotlib.pyplot.Axes, percentiles, scale: float = 1, color: typing.Optional[str] = None, label: typing.Optional[str] = None):
    percentiles = [p for p in percentiles if p["percentile"] != 1]
    percentiles_x = [p["percentile"] for p in percentiles]
    ax.plot(
        percentiles_x,
        [p["to"] * scale for p in percentiles],
        color=color,
        label=label
    )


def find_phase(run_data, name):
    selected_phase = None
    for phase in run_data["stats"]:
        if phase["name"] != name:
            continue
        selected_phase = phase
    return selected_phase


run_cache = {}


def load_run(benchmark_id: str):
    if benchmark_id not in run_cache:
        with open(f"output/{benchmark_id}/output.json") as f:
            run_cache[benchmark_id] = json.load(f)
    return run_cache[benchmark_id]


def get_index_property(index_item: dict, property: typing.Sequence[str]):
    v = index_item
    for key in property:
        if key not in v:
            return None
        v = v[key]
    return v


def as_properties(index_item: dict, _parent_path=()) -> typing.Dict[typing.Sequence[str], typing.Any]:
    res = {}
    for k, v in index_item.items():
        path = (*_parent_path, k)
        if path == ("name",):
            continue

        if type(v) is dict:
            res.update(as_properties(v, _parent_path=path))
        else:
            res[path] = v
    return res


def matches(expected: typing.Dict[typing.Sequence[str], typing.Any], index_item: dict):
    for k, v in expected.items():
        if get_index_property(index_item, k) != v:
            return True
    return False


def has_error(benchmark_id: str):
    if find_phase(load_run(benchmark_id), "main/0") is None:
        print(f"Benchmark run {benchmark_id} failed")
        return True
    else:
        return False


def combine_histograms(histograms):
    bucket_to = list(sorted(set([
        bucket["to"]
        for histogram in histograms
        for bucket in histogram
    ])))
    bucket_to.insert(0, 0)
    bucket_count = [0 for _ in bucket_to]
    total_count = 0

    for histogram in histograms:
        for in_bucket in histogram:
            from_bucket = bucket_to.index(in_bucket["from"])
            to_bucket = bucket_to.index(in_bucket["to"])
            total_count += in_bucket["count"]
            for i in range(from_bucket + 1, to_bucket + 1):
                bucket_count[i] += in_bucket["count"] * (bucket_to[i] - bucket_to[i - 1]) / (in_bucket["to"] - in_bucket["from"])

    out = []
    assert bucket_count[0] == 0
    count_so_far = 0
    for i in range(1, len(bucket_to)):
        count_so_far += bucket_count[i]
        out.append({
            "from": bucket_to[i - 1],
            "to": bucket_to[i],
            "percentile": count_so_far / total_count,
            "count": bucket_count[i],
            "totalCount": count_so_far
        })
    return out


def ns_to_str(ns: int) -> str:
    for factor_candidate, unit_candidate in ((10 ** 9, 's'), (10 ** 6, 'ms'), (10 ** 3, 'µs')):
        if factor_candidate <= ns:
            factor = factor_candidate
            unit = unit_candidate
            break
    else:
        factor = 1
        unit = 'ns'
    return f'{ns / factor:g}{unit}'


MODE_HISTOGRAM = "histogram"
MODE_SIMPLE = "simple"
SIMPLE_OPS = 1000


def main():
    with open("output/index.json") as f:
        index = json.load(f)
    index = sorted(index, key=lambda i: i["name"])
    index = [i for i in index if not has_error(i["name"])]
    discriminator_properties = [("type",), ("parameters", "compileConfiguration", "micronaut"), ("parameters", "compileConfiguration", "json"), ("parameters", "compileConfiguration", "transport"), ("parameters", "compileConfiguration", "tcnative")]
    filter_properties = {
        #("type",): "mn-hotspot"
        #("load", "protocol"): "HTTPS2"
    }
    combine_runs = True
    mode = MODE_HISTOGRAM

    def get_discriminator_tuple(index_item: dict):
        return tuple(get_index_property(index_item, k) for k in discriminator_properties)

    def find_baseline(index_item: dict):
        baseline_filter_props = as_properties(index_item)
        for disc in discriminator_properties:
            if disc in baseline_filter_props:
                del baseline_filter_props[disc]
        for candidate in index:
            if not matches(baseline_filter_props, candidate):
                return candidate

    max_percentile = 0
    max_time = 0
    min_time = None
    discriminated = []
    meta = None
    protocol_settings = None
    for run in index:
        if matches(filter_properties, run):
            continue
        protocol_settings = run["load"]["protocol"]
        run_data = load_run(run["name"])
        if meta is None:
            with open(f"output/{run['name']}/meta.json") as f:
                meta = json.load(f)
        for phase in run_data["stats"]:
            for percentile in phase["histogram"]["percentiles"]:
                if percentile["percentile"] != 1.0:
                    max_percentile = max(max_percentile, percentile["percentile"])
                max_time = max(max_time, percentile["to"])
                if min_time is None:
                    min_time = percentile["to"]
                else:
                    min_time = min(min_time, percentile["to"])
        discriminator_tuple = get_discriminator_tuple(run)
        if discriminator_tuple not in discriminated:
            discriminated.append(discriminator_tuple)
        max_time = 10*10**6
        max_percentile = 0.999
        print(min_time, max_time)
    colors_by_discriminator = {d: f'C{i}' for i, d in enumerate(discriminated)}

    if mode == MODE_HISTOGRAM:
        rows = 2
        cols = int(np.ceil(len(protocol_settings["ops"]) / rows))
        fig, axs = plt.subplots(rows, cols)
    elif mode == MODE_SIMPLE:
        ax = plt.subplot()

    for phase_i, ops in enumerate(protocol_settings["ops"]):
        phase = f"main/{phase_i}"
        if mode == MODE_HISTOGRAM:
            ax: matplotlib.axes.Axes = axs[phase_i // len(axs[0])][phase_i % len(axs[0])]
            percentile_ticks = list(generate_percentile_ticks(max_percentile))
            ax.set_xscale("function", functions=(percentile_transform, percentile_transform_reverse))
            ax.set_xticks(percentile_ticks, [str(p) for p in percentile_ticks])
            ax.set_xlim(0, max_percentile)
            ax.set_yscale("log")
            ax.set_ylabel("Request time")
            ax.yaxis.set_major_formatter(lambda ns, _: ns_to_str(ns))
            ax.set_ylim(min_time, max_time)

            any_shown = False
            for i, disc_tuple in enumerate(discriminated):
                runs_for_discriminator = [run for run in index if not matches(filter_properties, run) and get_discriminator_tuple(run) == disc_tuple]
                disc_histograms = []
                disc_medians = []
                disc_averages = []
                for run in runs_for_discriminator:
                    print(f"Plotting {phase} {run['name']}")
                    run_data = load_run(run["name"])
                    selected_phase = find_phase(run_data, phase)
                    if selected_phase is not None:
                        histogram = selected_phase["histogram"]["percentiles"]
                        disc_medians.append([p for p in histogram if p["percentile"] >= 0.5][0]["to"])
                        disc_averages.append(selected_phase["total"]["summary"]["meanResponseTime"])
                        if disc_histograms is not None:
                            disc_histograms.append(histogram)
                        if not combine_runs:
                            plot_histogram(
                                ax, histogram,
                                color=colors_by_discriminator[disc_tuple]
                            )
                            any_shown = True
                    else:
                        disc_histograms = None
                        print(" (no data)")
                if disc_histograms is not None and combine_runs:
                    plot_histogram(
                        ax, combine_histograms(disc_histograms),
                        color=colors_by_discriminator[disc_tuple],
                    )
                    any_shown = True
                if len(disc_medians) != 0:
                    ax.plot(
                        [percentile_transform_reverse(percentile_transform(max_percentile) - (i + 1) * 0.1) for _ in disc_medians],
                        disc_averages,
                        'x',
                        color=colors_by_discriminator[disc_tuple]
                    )
                    ax.plot(
                        [percentile_transform_reverse(percentile_transform(max_percentile) - (i + 1) * 0.1 - 0.05) for _ in disc_medians],
                        disc_medians,
                        '+',
                        color=colors_by_discriminator[disc_tuple]
                    )
                    any_shown = True
            if any_shown:
                ax.set_title(f"{ops} ops/s")

                ax.axvline(0.5, color='grey', linestyle=':')
                if phase_i == 0:
                    ax.legend(handles=[
                                          matplotlib.patches.Patch(color=v, label=" ".join(map(str, k)))
                                          for k, v in colors_by_discriminator.items()
                                      ] + [matplotlib.patches.Patch(
                        color="black",
                        label="average"
                    )])
            else:
                ax.remove()
        elif mode == MODE_SIMPLE:
            if ops == SIMPLE_OPS:
                ax: matplotlib.axes.Axes
                ax.set_title(f"{ops} ops/s")
                ax.set_ylabel("Request time")
                ax.yaxis.set_major_formatter(lambda ns, _: ns_to_str(ns))
                ax.tick_params(axis='x', rotation=90)
                ax.grid(axis="y")
                for i, disc_tuple in enumerate(discriminated):
                    runs_for_discriminator = [run for run in index if not matches(filter_properties, run) and get_discriminator_tuple(run) == disc_tuple]
                    run_data = [load_run(run["name"]) for run in runs_for_discriminator]
                    phase_data = [find_phase(run, phase) for run in run_data]
                    ax.boxplot([[phase["total"]["summary"]["meanResponseTime"] for phase in phase_data if phase is not None]], vert=True, positions=[i], labels=[" ".join(map(str, disc_tuple))], showbox=False, showmeans=True)

    if mode == MODE_HISTOGRAM:
        for r in range(rows):
            for c in range(cols):
                if c + r * rows >= len(protocol_settings["ops"]):
                    axs[r][c].remove()
        plt.xlabel("Percentile")
        plt.suptitle("""\
Request latency at different request rates. Each graph represents a fixed request rate.
Each request latency is recorded and shown in the graph. The horizontal axis is the latency percentile, the vertical axis the latency at that percentile.
For fairness, each framework is tested on the same infrastructure (server VM + client VMs) in random order. To reduce noise, the suite is repeated on independent infrastructures. The results of each infrastructure benchmark are combined to produce the plotted line.
A benchmark run fails when the server cannot keep up with requests. Should a framework fail at a given request rate on any infrastructure, its line is removed from the plot.
To visualize result spread, the median (+) and average (x) latency of each run is also shown. These are not merged between separate infrastructures, so if a framework only fails on one infra, median latency on the other infras is still shown.""",
                     ha="left", x=0)
    elif mode == MODE_SIMPLE:
        plt.tight_layout()
    plt.show()


main()
