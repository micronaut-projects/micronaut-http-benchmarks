package io.micronaut.benchmark.http.plot;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PySpyStackMergeTest {
    @Test
    void mergesWorkerStacksAndStripsProcessFrames() throws IOException {
        String merged = merge("""
                process 4754:"gunicorn: master [web]";process 4757:"gunicorn: worker [web]";process 4762:"gunicorn: worker [web]";_handle_request;app.dispatch_request 2
                process 4810:"gunicorn: master [web]";process 4812:"gunicorn: worker [web]";_handle_request;app.dispatch_request 3
                """);

        assertEquals("_handle_request;app.dispatch_request 5\n", merged);
    }

    @Test
    void removesAllLeadingProcessAncestryFrames() throws IOException {
        String merged = merge("""
                process 1:"emmett: master [app]";process 2:"emmett: worker [app]";process 3:"emmett: worker [app]";router;handler 7
                """);

        assertEquals("router;handler 7\n", merged);
    }

    @Test
    void keepsDistinctPythonStacksSeparateAndDeterministic() throws IOException {
        String merged = merge("""
                process 100:"gunicorn: worker [web]";view.users;db.query 4
                process 101:"gunicorn: worker [web]";view.orders;db.query 2
                process 102:"gunicorn: worker [web]";view.users;db.query 6
                """);

        assertEquals("view.users;db.query 10\nview.orders;db.query 2\n", merged);
    }

    @Test
    void skipsSamplesThatLoseAllPythonFramesAfterProcessRemoval() throws IOException {
        String merged = merge("""
                process 200:"gunicorn: worker [web]";process 201:"gunicorn: worker [web]" 5
                process 202:"gunicorn: worker [web]";router;handler 1
                """);

        assertEquals("router;handler 1\n", merged);
    }

    private static String merge(String input) throws IOException {
        StringWriter output = new StringWriter();
        try (BufferedReader reader = new BufferedReader(new StringReader(input))) {
            PySpyStackMerge.convert(reader, output);
        }
        return output.toString();
    }
}
