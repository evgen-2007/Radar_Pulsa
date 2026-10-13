package com.pulsbreath.core;

/** Shared sensor parsing + math pipeline used by both Android and Windows clients. */
public final class MeasurementPipeline {
    private final MathProcessor processor = new MathProcessor();
    public synchronized Result acceptJson(String json) {
        SensorPacket p = SensorPacket.parse(json);
        MathProcessor.ProcessResult r = processor.process(p.heart,p.heartValid,p.breath,p.breathValid,p.distanceCm,p.distanceValid);
        return new Result(p,r.live,r.completedMinute);
    }
    public synchronized void reset(){ processor.reset(); }
    public static final class Result {
        public final SensorPacket packet;
        public final MathProcessor.Measurement live;
        public final MathProcessor.MinuteSummary completedMinute;
        Result(SensorPacket p, MathProcessor.Measurement l, MathProcessor.MinuteSummary m){packet=p;live=l;completedMinute=m;}
    }
}
