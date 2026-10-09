package com.cdp.codpattern.app.zombies.service.navigation;

/** Paired fixed-target loops, moving-target pursuit and bounded waiting clocks. */
public final class RouteProgressCompatTest {
    public static void main(String[] args) {
        RouteProgress fixed = new RouteProgress(0, 0, 0, 0);
        fixed.target("player:floor", 5, 0, 5);
        for (int lap=0;lap<2;lap++) for (String edge : new String[]{"east","south","west","north"})
            fixed.completed(lap*240L, edge);
        require(fixed.loopDetected(), "two repeated circuits must be recognized before a third");
        fixed.waiting(500);
        long fixedRoute = fixed.lastRoute(), fixedWait = fixed.waitingUntil();
        int fixedHistory = fixed.historySize();
        fixed.sample(501,1,0,0);
        require(!fixed.advance(501,"new-native-self-node",Double.NaN,1,0,0)
                        && fixed.loopDetected() && fixed.lastRoute()==fixedRoute
                        && fixed.waitingUntil()==fixedWait && fixed.historySize()==fixedHistory,
                "an undefined native self-node projection cannot erase an established loop or renew any clock");

        RouteProgress boundary = new RouteProgress(0,0,0,0);
        boundary.target("same-player",7.99,0,0);
        for (int lap=0;lap<2;lap++) {
            boundary.completed(lap*240L,"east");
            boundary.target("same-player",lap==0?8.01:7.99,0,0);
            boundary.completed(lap*240L+120,"west");
        }
        require(boundary.loopDetected(), "tiny target motion across tile boundaries must retain loop evidence");

        RouteProgress jitter = new RouteProgress(0,0,0,0);
        jitter.target("same-player",7.99,0,0);
        for (int tick=1;tick<=1000;tick++) {
            jitter.target("same-player",tick%2==0?7.99:8.01,0,0);
            if (tick%250==0) jitter.completed(tick,tick%500==0?"west":"east");
        }
        require(jitter.loopDetected(), "long-lived sub-block jitter must not erase a slow repeated circuit");

        RouteProgress jumping = new RouteProgress(0,0,0,0);
        jumping.target("same-player",0,0,0);
        for (int tick=1;tick<=1000;tick++) {
            jumping.target("same-player",0,tick%10<5?1.25:0,0);
            if (tick%250==0) jumping.completed(tick,tick%500==0?"west":"east");
        }
        require(jumping.loopDetected(), "jumping in place must not erase a repeated circuit");
        jumping.target("same-player",2,0,0);
        require(!jumping.loopDetected(), "a destination two blocks from the previous anchor begins a new pursuit context");

        RouteProgress longCycle = new RouteProgress(0,0,0,0);
        longCycle.target("fixed-player",0,0,0);
        for (int lap=0;lap<2;lap++) for (int edge=0;edge<96;edge++) {
            longCycle.completed(lap*480L+edge*5L,"edge-"+edge);
            if (edge%16==0) longCycle.portal("exit-"+edge);
        }
        require(longCycle.loopDetected(), "portal evidence retains circuits longer than the fine-edge window");
        RouteProgress exits = new RouteProgress(0,0,0,0);
        exits.target("fixed-player",0,0,0);
        for (int i=0;i<8;i++) exits.portal((i%2==0?"tile-A>B:":"tile-B>A:")+"different-exit-"+i);
        require(!exits.loopDetected(), "different real exits between the same tiles are new exploration");

        RouteProgress pursuit = new RouteProgress(0,0,0,0);
        pursuit.target("player:floor",5,0,5);
        for (int lap=0;lap<4;lap++) {
            for (String edge : new String[]{"east","south","west","north"}) {
                pursuit.target("player:floor",5 + lap*4,0,5);
                pursuit.completed(lap*240L,edge);
            }
            require(!pursuit.loopDetected(), "substantial target movement permits a legitimate repeated circuit");
        }
        RouteProgress wait = new RouteProgress(10,0,0,0);
        wait.waiting(100);
        require(wait.waitingUntil()==650, "one planning allowance adds 320 ticks to the ordinary timeout");
        wait.waiting(649); wait.waiting(10000);
        require(wait.waitingUntil()==650, "replanning/expired waits cannot renew allowance");
        wait.engagement(10001); wait.waiting(10002);
        require(wait.waitingUntil()==10641, "real engagement restores one waiting allowance");

        RouteProgress slow = new RouteProgress(0,0,0,0);
        for (int tick=1;tick<=800;tick++) {
            slow.sample(tick,tick*.005,0,0);
            slow.advance(tick,"edge",tick*.005,tick*.005,0,0);
        }
        require(slow.lastRoute()>=799, "per-tick sampling retains accumulated slow route advancement");
        for (int tick=801;tick<1801;tick++) {
            slow.sample(tick,4,0,0);
            slow.advance(tick,"edge",4,4,0,0);
        }
        require(slow.lastRoute()<=800, "new plan IDs or repeated old edge projections cannot credit standing still");
        for (int i=0;i<1000;i++) slow.completed(i,"edge-"+i);
        require(slow.historySize()<=256,"structural history is bounded");
        nativePathReplacementCannotRewardReturningToAnOldEndpoint();
        exhaustedCandidateRoundCannotRevivePlanningAllowance();
        productiveSearchHasAFiniteIndependentLease();
        unchangedOrExpiredSearchCannotRenewItsLease();
        replacementSearchAndTargetChangesCannotRenewTheLease();
        inactiveSearchCannotBankDiscoveredNodes();
        retiredSearchNeedsRealProgressBeforeAnotherLease();
        readyRouteRetainsOnlyItsEarnedSearchLease();
        System.out.println("RouteProgressCompatTest: passed");
    }
    private static void productiveSearchHasAFiniteIndependentLease() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        progress.searchActivity(1,1,0,true);
        for (int tick=20;tick<=4000;tick+=20) {
            progress.searchActivity(tick,1,tick/20L*16,true);
            require(progress.planningWaitUntil()<=3200,
                    "even infinitely many newly discovered nodes cannot exceed the fixed search deadline");
            if (tick==800) require(progress.planningWaitUntil()>800,
                    "a continuously advancing cold search may outlive the ordinary planning allowance");
        }
        require(progress.planningWaitUntil()==3200 && progress.waitingUntil()==640
                        && progress.lastRoute()==0 && progress.lastCombat()==0,
                "search activity must preserve the original wait and actual movement/combat clocks");
    }
    private static void unchangedOrExpiredSearchCannotRenewItsLease() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        progress.searchActivity(1,1,0,true);
        progress.searchActivity(600,1,16,true);
        require(progress.planningWaitUntil()==760,"new graph records earn only a short search lease");
        for (int tick=601;tick<=1000;tick++) progress.searchActivity(tick,1,16,true);
        require(progress.planningWaitUntil()==760,
                "no new records, including during final route reconstruction, cannot renew the lease");
        progress.searchActivity(1001,1,32,true);
        require(progress.planningWaitUntil()==760,"new discoveries cannot revive an expired search lease");

        RouteProgress expired = new RouteProgress(0,0,0,0);
        expired.waiting(1);
        expired.searchActivity(640,2,0,true);
        expired.searchActivity(650,2,1000,true);
        require(expired.planningWaitUntil()==640,
                "an already expired original allowance cannot be rescued by a late first search observation");
    }
    private static void replacementSearchAndTargetChangesCannotRenewTheLease() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        progress.searchActivity(1,1,0,true);
        progress.searchActivity(600,1,16,true);
        for (int tick=620;tick<=1000;tick+=20) {
            progress.target("player-"+tick,tick,0,0);
            progress.waiting(tick);
            progress.searchActivity(tick,tick,1000+tick,true);
        }
        require(progress.planningWaitUntil()==760 && progress.lastRoute()==0,
                "restarts, alternate targets and target motion cannot replace the bound search or extend its deadline");
    }
    private static void inactiveSearchCannotBankDiscoveredNodes() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        progress.searchActivity(1,1,0,true);
        progress.searchActivity(600,1,16,false);
        progress.searchActivity(620,1,16,true);
        require(progress.planningWaitUntil()==640,
                "chunk/resource waiting cannot earn a lease, even after returning to active with the same count");
        progress.searchActivity(625,1,24,true);
        progress.searchActivity(630,1,24,false);
        progress.searchActivity(639,1,32,true);
        require(progress.planningWaitUntil()==640,
                "an inactive interval discards a partial batch rather than banking its old discoveries");
        progress.searchActivity(640,1,64,true);
        require(progress.planningWaitUntil()==640,"inactivity cannot be hidden by discoveries at the expired deadline");
    }
    private static void retiredSearchNeedsRealProgressBeforeAnotherLease() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        progress.searchActivity(1,1,0,true);
        progress.searchActivity(600,1,16,true);
        progress.retireWaiting();
        progress.waiting(620);
        progress.searchActivity(620,1,32,true);
        progress.searchActivity(630,2,100,true);
        require(progress.planningWaitUntil()==0,"conclusive exhaustion retires both waiting allowances");

        progress.sample(1000,1,0,0);
        require(progress.advance(1000,"new-route",1,1,0,0),"the new lease requires real directed route movement");
        progress.waiting(1001);
        progress.searchActivity(1001,2,100,true);
        progress.searchActivity(1600,2,116,true);
        require(progress.lastRoute()==1000 && progress.waitingUntil()==1640 && progress.planningWaitUntil()==1760,
                "real movement permits a new search with a fresh, separate finite lease");

        progress.retireWaiting();
        progress.engagement(2000);
        progress.waiting(2001);
        progress.searchActivity(2001,3,0,true);
        progress.searchActivity(2600,3,16,true);
        require(progress.lastCombat()==2000 && progress.waitingUntil()==2640 && progress.planningWaitUntil()==2760,
                "real combat likewise restores one new search allowance");
    }
    private static void readyRouteRetainsOnlyItsEarnedSearchLease() {
        RouteProgress progress = new RouteProgress(0,0,0,0);
        progress.waiting(1);
        require(progress.planningWaitUntil(true)==640 && progress.planningWaitUntil(false)==0,
                "READY alone must not inherit the ordinary planning allowance");
        progress.searchActivity(1,1,0,true);
        progress.searchActivity(600,1,16,true);
        // A cold route is now READY and its first edge is CLEAR, but physics has
        // not moved the entity before this tick's recycler takes its snapshot.
        progress.searchActivity(640,1,16,false);
        require(progress.planningWaitUntil(false)==760 && progress.lastRoute()==0,
                "READY handoff retains the already-earned lease without pretending the monster moved");
        progress.searchActivity(759,1,16,false);
        require(progress.planningWaitUntil(false)==760,"READY does not renew the retained lease");
        progress.searchActivity(760,1,32,true);
        require(progress.planningWaitUntil(false)==760,"the retained READY lease cannot revive at expiry");

        progress.sample(780,1,0,0);
        require(progress.advance(780,"first-ready-edge",1,1,0,0),"the first real route movement must be credited");
        require(progress.planningWaitUntil(false)==0 && progress.planningWaitUntil(true)==0,
                "real route movement clears both the handoff lease and old planning allowance");
        progress.waiting(781);
        progress.searchActivity(781,2,0,true);
        progress.searchActivity(1380,2,16,true);
        require(progress.planningWaitUntil(false)==1540,"a later cold search can earn its own handoff lease");
        progress.retireWaiting();
        require(progress.planningWaitUntil(false)==0 && progress.planningWaitUntil(true)==0,
                "conclusive exhaustion also clears any lease retained through READY");
    }
    private static void exhaustedCandidateRoundCannotRevivePlanningAllowance() {
        RouteProgress progress = new RouteProgress(5739,0,0,0);
        progress.target("primary-player",5,0,0);
        progress.sample(6253,1,0,0);
        require(progress.advance(6253,"new-native-edge",1,1,0,0),
                "the fixed-target reproduction first earns genuine route progress");
        progress.waiting(6260);
        require(progress.waitingUntil()==6893,"pending computation has its original finite allowance");
        // A primary failure can still leave an untested alternative. That candidate
        // shares the existing allowance; neither changing target nor rechecking extends it.
        progress.target("alternative-player",6,0,0);
        progress.waiting(6300);
        require(progress.waitingUntil()==6893,"an alternative candidate keeps, but never renews, the same allowance");
        progress.retireWaiting();
        for (int tick=6360;tick<=7100;tick++) {
            progress.sample(tick,1,0,0);
            progress.waiting(tick);
        }
        require(progress.waitingUntil()==0 && progress.lastRoute()==6253,
                "conclusive exhaustion ends waiting even when low-frequency rounds keep rechecking the map");
        progress.sample(7101,1.3,0,0);
        require(progress.advance(7101,"new-exit",.3,1.3,0,0),"a newly explored exit supplies actual useful motion");
        progress.waiting(7102);
        require(progress.waitingUntil()==7741,"real route progress restores one allowance after exhaustion");
        progress.retireWaiting(); progress.engagement(7200); progress.waiting(7201);
        require(progress.waitingUntil()==7840,"real combat can also restore an allowance after exhaustion");
    }
    private static void nativePathReplacementCannotRewardReturningToAnOldEndpoint() {
        RouteProgress fixed = new RouteProgress(0, 374.5, 0, 0);
        fixed.target("fixed-enclosed-player", 375.5, 0, 6);
        String edge = "native:374>375";
        for (int tick=1;tick<=20;tick++) {
            double x = 374.5 + tick * .05;
            fixed.sample(tick, x, 0, 0);
            fixed.advance(tick, edge, NativeRouteProgress.projection(374.5,0,0,375.5,0,0,x,0,0), x,0,0);
        }
        long last = fixed.lastRoute(); fixed.waiting(30); long waiting = fixed.waitingUntil();
        require(last > 0, "the first real approach to an endpoint advances the route clock");
        for (int tick=21;tick<=600;tick++) fixed.sample(tick,375.5,0,0);
        for (int tick=601;tick<=680;tick++) {
            // A native path replacement starts at the retreat position, but the directed
            // node edge's measurement origin remains 374.5 rather than that new position.
            double x = tick <= 640 ? 375.5 - (tick-600)*.0425 : 373.8 + (tick-640)*.0425;
            fixed.sample(tick,x,0,0);
            fixed.advance(tick,edge,NativeRouteProgress.projection(374.5,0,0,375.5,0,0,x,0,0),x,0,0);
            fixed.waiting(tick);
        }
        require(fixed.lastRoute() == last && fixed.waitingUntil() == waiting,
                "retreating and returning along the same canonical native edge cannot renew progress or planning grace");
        for (int tick=681;tick<=700;tick++) {
            double x = 375.5 + (tick-680)*.05;
            fixed.sample(tick,x,0,0);
            fixed.advance(tick,"native:375>376",NativeRouteProgress.projection(375.5,0,0,376.5,0,0,x,0,0),x,0,0);
        }
        require(fixed.lastRoute() > last, "a genuinely new directed edge still permits useful progress");
        require(Double.isNaN(NativeRouteProgress.projection(1,0,0,1,0,0,1.2,0,0)),
                "a native path's initial self node cannot award arbitrary motion from a new origin");
    }
    private static void require(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
