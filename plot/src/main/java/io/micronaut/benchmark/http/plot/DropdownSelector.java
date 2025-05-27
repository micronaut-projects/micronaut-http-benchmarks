package io.micronaut.benchmark.http.plot;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DropdownSelector {
    private final String bodyAttribute = "ds-" + UUID.randomUUID();
    private final List<Option> options = new ArrayList<>();

    public DropdownSelector() {
    }

    public OptionAttribute addOption(String name) {
        String cl = "ds-" + UUID.randomUUID();
        options.add(new Option(name, cl));
        return new OptionAttribute(cl);
    }

    public void emitHead(StringBuilder html) {
        html.append("<style>");
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            html.append("body:not([").append(bodyAttribute).append("=\"").append(option.htmlClass).append("\"])");
            if (i == 0) {
                // show first option by default, when the body attribute is unset
                html.append("[").append(bodyAttribute).append("]");
            }
            html.append(" .").append(option.htmlClass).append("{display:none;}");
        }
        html.append("</style>");
    }

    public void emitSelect(StringBuilder html) {
        html.append("<select autocomplete='off' onchange='document.body.setAttribute(\"").append(bodyAttribute).append("\", this.value)'>");
        for (int i = 0; i < options.size(); i++) {
            Option option = options.get(i);
            html.append("<option value=\"").append(option.htmlClass).append("\"");
            if (i == 0) {
                html.append(" selected");
            }
            html.append(">").append(option.name).append("</option>");
        }
        html.append("</select>");
    }

    public void emitSelectSpecific(StringBuilder js, OptionAttribute attr) {
        js.append("document.body.setAttribute(\"").append(bodyAttribute).append("\", \"").append(attr.htmlClass).append("\")");
    }

    private record Option(
            String name,
            String htmlClass
    ) {
    }

    public record OptionAttribute(String htmlClass) {
    }
}
