import {signal} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {of, Subject, throwError} from 'rxjs';
import {UserService} from '../../../settings/user-management/user.service';
import {CatalogueGroup, CataloguePreview, CatalogueReconciliationService, CatalogueScan} from '../../service/catalogue-reconciliation.service';
import {CatalogueReconciliationComponent} from './catalogue-reconciliation.component';

describe('CatalogueReconciliationComponent', () => {
  const user = signal({permissions: {admin: true}});
  const api = {scan: vi.fn(), preview: vi.fn(), merge: vi.fn(), receipt: vi.fn(), receipts: vi.fn()};
  const group: CatalogueGroup = {key: 'shared', path: '/books/shared.epub', suggestedTargetBookId: 1,
    members: [{bookId: 1, fileId: 11, title: 'Book', authors: ['Ada'], storedHash: 'same'},
      {bookId: 2, fileId: 22, title: 'Book', authors: ['Ada'], storedHash: 'same'}]};
  const report: CatalogueScan = {libraryId: 7, page: 0, size: 20, totalGroups: 1, extraRecords: 1, groups: [group]};
  const preview: CataloguePreview = {libraryId: 7, targetBookId: 1, members: group.members, path: group.path,
    fileSha256: 'verified', fileBytes: 123, eligible: true, reasons: [], relatedRecords: {}, metadataDifferences: {}, token: 'snapshot-token'};
  let component: CatalogueReconciliationComponent;

  beforeEach(() => {
    vi.resetAllMocks(); user.set({permissions: {admin: true}});
    api.scan.mockReturnValue(of(report)); api.preview.mockReturnValue(of(preview));
    TestBed.configureTestingModule({providers: [
      {provide: CatalogueReconciliationService, useValue: api},
      {provide: UserService, useValue: {currentUser: user}},
      {provide: TranslocoService, useValue: {translate: (key: string) => key}},
    ]});
    component = TestBed.runInInjectionContext(() => new CatalogueReconciliationComponent());
    component.libraryId = 7; component.ngOnChanges();
  });
  afterEach(() => TestBed.resetTestingModule());

  it('does not scan or mutate anything on opening', () => {
    expect(api.scan).not.toHaveBeenCalled(); expect(api.merge).not.toHaveBeenCalled();
  });
  it('scan and inspection never merge without explicit confirmation', () => {
    component.scan(); component.inspect(group); component.merge();
    expect(component.inspected()?.token).toBe('snapshot-token');
    expect(component.confirmed).toBe(false); expect(api.merge).not.toHaveBeenCalled();
  });
  it('cannot merge a conflicting group even if the checkbox is set programmatically', () => {
    api.preview.mockReturnValue(of({...preview, eligible: false, reasons: ['Conflicting progress']}));
    component.inspect(group); component.confirmed = true; component.merge();
    expect(api.merge).not.toHaveBeenCalled();
  });
  it('changing the survivor clears confirmation and prevents use of the old preview', () => {
    component.inspect(group); component.confirmed = true;
    const pending = new Subject<CataloguePreview>(); api.preview.mockReturnValue(pending);
    component.selectTarget(2); component.merge();
    expect(component.confirmed).toBe(false); expect(component.inspected()).toBeNull();
    expect(api.merge).not.toHaveBeenCalled();
    pending.next({...preview, targetBookId: 2, token: 'new-token'}); pending.complete();
    expect(component.inspected()?.targetBookId).toBe(2); expect(component.confirmed).toBe(false);
  });
  it('keeps a receipt, resets confirmation, and rescans after one confirmed merge', () => {
    api.merge.mockReturnValue(of({id: 'receipt', targetBookId: 1, removedBookIds: [2], fileSha256: 'verified', completedAt: 'now'}));
    api.scan.mockReturnValueOnce(of(report)).mockReturnValueOnce(of({...report, totalGroups: 0, extraRecords: 0, groups: []}));
    const event = vi.spyOn(component.mergingChange, 'emit');
    component.scan(); component.inspect(group); component.confirmed = true; component.merge();
    expect(api.merge).toHaveBeenCalledExactlyOnceWith(preview);
    expect(component.lastReceipt()?.id).toBe('receipt'); expect(component.confirmed).toBe(false);
    expect(component.report()?.totalGroups).toBe(0); expect(api.scan).toHaveBeenCalledTimes(2);
    expect(event.mock.calls).toEqual([[true], [false]]);
  });
  it('clears a stale preview on conflict and never retries a merge automatically', () => {
    api.merge.mockReturnValue(throwError(() => ({error: {message: 'The file changed'}})));
    component.inspect(group); component.confirmed = true; component.merge();
    expect(component.error()).toBe('The file changed'); expect(component.inspected()).toBeNull();
    expect(component.confirmed).toBe(false); expect(api.merge).toHaveBeenCalledTimes(1);
  });
  it('ignores late responses from a previous library', () => {
    const pending = new Subject<CataloguePreview>(); api.preview.mockReturnValue(pending);
    component.inspect(group); component.libraryId = 8; component.ngOnChanges();
    pending.next(preview); pending.complete();
    expect(component.inspected()).toBeNull(); expect(component.report()).toBeNull();
  });
  it('does not request reconciliation data for a non-administrator', () => {
    user.set({permissions: {admin: false}});
    component.scan(); component.inspect(group); component.loadHistory();
    expect(api.scan).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled(); expect(api.receipts).not.toHaveBeenCalled();
  });
});
