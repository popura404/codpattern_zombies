package com.cdp.codpattern.client.zombies;

import com.cdp.codpattern.app.zombies.service.ZombiesObjectLabelWorldRendererStaticContractCompatTest;

public final class ZombiesObjectLabelCompatSuite {
    private ZombiesObjectLabelCompatSuite() { }

    public static void main(String[] args) throws Exception {
        ZombiesObjectLabelLayoutCompatTest.main(args);
        ZombiesLabelProjectionCompatTest.main(args);
        ZombiesObjectLabelWorldRendererStaticContractCompatTest.main(args);
        System.out.println("PASS zombies object label compatibility suite");
    }
}
