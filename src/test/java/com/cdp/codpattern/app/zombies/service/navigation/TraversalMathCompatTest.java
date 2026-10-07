package com.cdp.codpattern.app.zombies.service.navigation;

import java.util.List;

public final class TraversalMathCompatTest {
    private TraversalMathCompatTest() { }
    public static void main(String[] args) {
        continuousSupportNeverBridgesAGap();
        overlapIsRotationAndMirrorInvariant();
        aLongDropResumesOneSliceAtATime();
        smallBudgetsChangeTimeButNotTheScannedRange();
        horizontalVelocityHasAFiniteConservativeEnvelope();
    }

    private static void continuousSupportNeverBridgesAGap() {
        require(close(TraversalMath.supportedPrefix(List.of(
                new TraversalMath.Interval(0.5,1),new TraversalMath.Interval(0,0.5))),1),
                "touching support intervals form a continuous path");
        require(close(TraversalMath.supportedPrefix(List.of(
                new TraversalMath.Interval(0,0.4),new TraversalMath.Interval(0.6,1))),0.4),
                "two valid endpoints must not prove a walk across a gap");
        require(close(TraversalMath.supportedPrefix(List.of(new TraversalMath.Interval(0.2,1))),0),
                "the supported path must begin at the actual start");
        require(close(TraversalMath.supportedPrefix(List.of()),0),"empty support cannot prove walking");
    }

    private static void overlapIsRotationAndMirrorInvariant() {
        var base=TraversalMath.overlap(0,0,2,1,0.5,1.5,0.25,0.75);
        var rotated=TraversalMath.overlap(0,0,-1,2,-0.75,-0.25,0.5,1.5);
        var mirrored=TraversalMath.overlap(0,0,-2,1,-1.5,-0.5,0.25,0.75);
        require(base!=null && rotated!=null && mirrored!=null,"transforms must retain intersections");
        require(close(base.start(),0.25) && close(base.end(),0.75),"exact entry/exit interval expected");
        require(base.equals(rotated) && base.equals(mirrored),"shape rotation and mirroring must be symmetric");
        require(TraversalMath.overlap(0,0,0,1,0.5,1.5,0,1)==null,
                "parallel motion outside support stays unsupported");
    }

    private static void aLongDropResumesOneSliceAtATime() {
        var scan=new TraversalMath.DropScanRange(319.75,-64);
        require(close(scan.bottom(),318.75),"one work step is one block, never the whole shaft");
        scan.advance();
        require(close(scan.top(),318.75) && !scan.done(),"cursor must retain the next slice across ticks");
        int steps=1;
        while (!scan.done()) { scan.advance(); steps++; }
        require(steps==384 && close(scan.top(),-64),"deep scan must reach bounds without 16/32/64 height caps");
        scan.advance();
        require(close(scan.top(),-64),"completed scan cannot cross the map floor");
    }

    private static void smallBudgetsChangeTimeButNotTheScannedRange() {
        long small=scanFingerprint(1),large=scanFingerprint(17);
        require(small==large,"budget must not change visited vertical slices or the reachability evidence");
    }
    private static long scanFingerprint(int budget) {
        var scan=new TraversalMath.DropScanRange(200.5,-63.75);
        long hash=17;
        while (!scan.done()) for (int unit=0;unit<budget && !scan.done();unit++) {
            hash=31*hash+Double.doubleToLongBits(scan.top());
            scan.advance();
        }
        return hash;
    }

    private static void horizontalVelocityHasAFiniteConservativeEnvelope() {
        require(close(TraversalMath.residualDrift(0.018),0.2),"drift includes every future drag step");
        require(close(TraversalMath.residualDrift(-0.018),0.2),"mirror directions have the same envelope");
        require(TraversalMath.residualDrift(0.1)>1,"normal walking speed cannot be treated as a vertical ray");
    }
    private static boolean close(double a,double b) { return Math.abs(a-b)<1.0E-8; }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
