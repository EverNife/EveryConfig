package br.com.finalcraft.everyconfig.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Collects the warnings a suite wants to count, so "once per site"/"once per type" is asserted on the log
 *  itself rather than on a counter the production code would have to expose. */
final class LogRecorder extends Handler {

    final List<String> records = new ArrayList<>();

    private final Logger target;

    private LogRecorder(final Logger target) {
        this.target = target;
    }

    static LogRecorder attachedTo(final Class<?> source) {
        final LogRecorder recorder = new LogRecorder(Logger.getLogger(source.getName()));
        recorder.target.addHandler(recorder);
        return recorder;
    }

    void detach() {
        target.removeHandler(this);
    }

    @Override
    public void publish(final LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
            records.add(record.getMessage());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
