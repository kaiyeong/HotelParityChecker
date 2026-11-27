package com.hotel.parity.service;

import com.hotel.parity.config.AppConfig;
import com.hotel.parity.model.HotelProperty;
import org.junit.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EmailNotificationServiceTest {

    @Test
    public void testSupportsMultipleDefaultEmailsAndCcRouting() {
        Properties props = new Properties();
        props.setProperty("rm.email.default", "revenue@company.com; hq@company.com , ops@company.com");
        AppConfig config = new AppConfig(props);

        assertEquals("revenue@company.com, hq@company.com, ops@company.com", config.getDefaultRmEmails());

        ProviderIdResolverService mockResolver = mock(ProviderIdResolverService.class);
        when(mockResolver.getRmEmailsForProperty("CLHOT")).thenReturn("rm@classiquehotel.com, gm@classiquehotel.com");
        when(mockResolver.getRmEmailsForProperty("TROVE")).thenReturn(null);

        EmailNotificationService service = new EmailNotificationService(config, mockResolver);

        HotelProperty clhot = new HotelProperty();
        clhot.setId("CLHOT");
        EmailNotificationService.RecipientRouting clhotRouting = service.resolveRecipientRouting(clhot);
        assertEquals("rm@classiquehotel.com, gm@classiquehotel.com", clhotRouting.getTo());
        assertEquals("revenue@company.com, hq@company.com, ops@company.com", clhotRouting.getCc());

        HotelProperty trove = new HotelProperty();
        trove.setId("TROVE");
        EmailNotificationService.RecipientRouting troveRouting = service.resolveRecipientRouting(trove);
        assertEquals("revenue@company.com, hq@company.com, ops@company.com", troveRouting.getTo());
        assertEquals(null, troveRouting.getCc());
    }
}
