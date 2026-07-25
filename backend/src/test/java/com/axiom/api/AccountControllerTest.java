package com.axiom.api;

import com.axiom.accounts.AccountService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountControllerTest {
    @Test void delegatesListDetailAndHierarchy() {
        QueryService queries = mock(QueryService.class);
        AccountService accounts = mock(AccountService.class);
        AccountController controller = new AccountController(queries, accounts);
        UUID id = UUID.randomUUID();
        PageResult<QueryService.AccountRow> page = PageResult.of(List.of(), 0, 100, 0);
        AccountService.AccountDetail detail = mock(AccountService.AccountDetail.class);
        AccountService.HierarchyView hierarchy = mock(AccountService.HierarchyView.class);

        when(queries.listAccounts("steel", "Manufacturing", 0)).thenReturn(page);
        when(accounts.get(id)).thenReturn(detail);
        when(accounts.hierarchy(id)).thenReturn(hierarchy);

        assertEquals(page, controller.list("steel", "Manufacturing", 0));
        assertEquals(detail, controller.detail(id));
        assertEquals(hierarchy, controller.hierarchy(id));
        verify(queries).listAccounts("steel", "Manufacturing", 0);
        verify(accounts).get(id);
        verify(accounts).hierarchy(id);
    }
}
