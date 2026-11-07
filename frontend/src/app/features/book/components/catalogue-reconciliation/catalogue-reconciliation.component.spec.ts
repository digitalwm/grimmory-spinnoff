import {signal, SimpleChange} from '@angular/core';
import {TestBed} from '@angular/core/testing';
import {TranslocoService} from '@jsverse/transloco';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {of, Subject, throwError} from 'rxjs';
import {UserService} from '../../../settings/user-management/user.service';
import {CatalogueGroup, CataloguePreview, CatalogueReconciliationService, CatalogueScan} from '../../service/catalogue-reconciliation.service';
import {CatalogueReconciliationComponent} from './catalogue-reconciliation.component';

describe('CatalogueReconciliationComponent', () => {
  const user = signal({permissions: {admin: true}});
  const api = {scan: vi.fn(), preview: vi.fn(), merge: vi.fn(), receipt: vi.fn(), receipts: vi.fn(), quarantinedFile: vi.fn(), quickMerge: vi.fn()};
  const group: CatalogueGroup = {key: 'shared', path: '/books/shared.epub', suggestedTargetBookId: 1,
    members: [{bookId: 1, fileId: 11, title: 'Book', authors: ['Ada'], storedHash: 'same'},
      {bookId: 2, fileId: 22, title: 'Book', authors: ['Ada'], storedHash: 'same'}]};
  const report: CatalogueScan = {libraryId: 7, page: 0, size: 20, totalGroups: 1, extraRecords: 1, groups: [group]};
  const preview: CataloguePreview = {libraryId: 7, targetBookId: 1, members: group.members, path: group.path,
    fileSha256: 'verified', fileBytes: 123, eligible: true, reasons: [], relatedRecords: {}, metadataDifferences: {}, token: 'snapshot-token'};
  let component: CatalogueReconciliationComponent;

  beforeEach(() => {
    vi.resetAllMocks(); user.set({permissions: {admin: true}});
    api.scan.mockReturnValue(of(report)); api.preview.mockReturnValue(of(preview)); api.receipt.mockReturnValue(of({quarantine: []}));
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
  it('keeps a receipt and updates the existing list without another full scan', () => {
    api.merge.mockReturnValue(of({id: 'receipt', targetBookId: 1, removedBookIds: [2], fileSha256: 'verified', completedAt: 'now'}));
    api.scan.mockReturnValueOnce(of(report)).mockReturnValueOnce(of({...report, totalGroups: 0, extraRecords: 0, groups: []}));
    const event = vi.spyOn(component.mergingChange, 'emit');
    component.scan(); component.inspect(group); component.confirmed = true; component.merge();
    expect(api.merge).toHaveBeenCalledExactlyOnceWith(preview);
    expect(component.lastReceipt()?.id).toBe('receipt'); expect(component.confirmed).toBe(false);
    expect(component.report()?.totalGroups).toBe(0); expect(api.scan).toHaveBeenCalledTimes(1);
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
  it('opens the selected formats group directly as a read-only preview', () => {
    component.reviewGroup = group; component.ngOnChanges();
    expect(api.preview).toHaveBeenCalledExactlyOnceWith(7, group, 1);
    expect(api.scan).not.toHaveBeenCalled(); expect(api.merge).not.toHaveBeenCalled();
  });
  it('includes title search in the bounded scan', () => {
    component.search = 'A Tough Man to Tame'; component.scan();
    expect(api.scan).toHaveBeenCalledExactlyOnceWith(7, 0, 'A Tough Man to Tame', 0); expect(api.merge).not.toHaveBeenCalled();
  });
  it('shows the durable quarantine state after a successful merge', () => {
    api.receipt.mockReturnValue(of({quarantine:[{id:'copy',state:'QUARANTINED',purge_after:'2026-12-07'}]}));
    component.loadQuarantine('receipt'); expect(component.quarantined()[0].id).toBe('copy');
    expect(api.merge).not.toHaveBeenCalled();
  });
  it('does not request reconciliation data for a non-administrator' , () => {
    user.set({permissions: {admin: false}});
    component.scan(); component.inspect(group); component.loadHistory();
    expect(api.scan).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled(); expect(api.receipts).not.toHaveBeenCalled();
  });
  it('keeps the scanned list when a formats group is opened and cleared', () => {
    component.scan();component.reviewGroup=group;
    component.ngOnChanges({reviewGroup:new SimpleChange(null,group,false)});
    expect(component.report()).toBe(report);
    component.reviewGroup=null;component.ngOnChanges({reviewGroup:new SimpleChange(group,null,false)});
    expect(component.report()).toBe(report);expect(api.scan).toHaveBeenCalledTimes(1);
  });
  it('continues pagination from the remaining rows without skipping candidates after a merge', () => {
    const groups=[group,...Array.from({length:19},(_,index)=>({...group,key:'group-'+index,members:group.members.map(m=>({...m,bookId:m.bookId+10+index*2}))}))];
    api.scan.mockReturnValue(of({...report,groups,totalGroups:40,extraRecords:40,offset:0}));
    api.merge.mockReturnValue(of({id:'receipt',targetBookId:1,removedBookIds:[2],fileSha256:'verified',completedAt:'now'}));
    component.scan();component.inspect(group);component.confirmed=true;component.merge();
    expect(component.report()?.groups).toHaveLength(19);expect(api.scan).toHaveBeenCalledTimes(1);
    component.scan(1,true);expect(api.scan).toHaveBeenLastCalledWith(7,1,'',19);
  });

  it('merges a straightforward catalogue group with one click and no confirmation checkbox', () => {
    api.quickMerge.mockReturnValue(of({status:'MERGED',receipt:{id:'receipt',targetBookId:1,removedBookIds:[2],fileSha256:'verified',completedAt:'now'},preview:null,reviewReasons:[]}));
    component.scan();component.quickMerge(group);
    expect(component.confirmed).toBe(false);expect(component.inspected()).toBeNull();expect(component.report()?.groups).toHaveLength(0);
    expect(api.preview).not.toHaveBeenCalled();expect(api.merge).not.toHaveBeenCalled();expect(api.scan).toHaveBeenCalledTimes(1);
  });
  it('keeps a blocking assessment inline and opens it only through explicit inspect', () => {
    api.quickMerge.mockReturnValue(of({status:'REVIEW_REQUIRED',receipt:null,preview,reviewReasons:['Different editions']}));
    component.quickMerge(group);expect(component.inspected()).toBeNull();expect(component.assessments()[group.key].preview).toBe(preview);
    component.quickMerge(group);expect(api.quickMerge).toHaveBeenCalledTimes(1);
    component.inspect(group);expect(component.inspected()).toBe(preview);expect(component.confirmed).toBe(false);
    expect(api.preview).not.toHaveBeenCalled();expect(api.merge).not.toHaveBeenCalled();
  });
  it('uses a parent assessment directly and prunes groups resolved in the other view', () => {
    component.scan();component.reviewGroup=group;component.reviewAssessment={status:'REVIEW_REQUIRED',receipt:null,preview,reviewReasons:['Different editions']};
    component.ngOnChanges({reviewGroup:new SimpleChange(null,group,false)});expect(api.preview).not.toHaveBeenCalled();
    component.resolvedBookIds=[1,2];component.ngOnChanges({resolvedBookIds:new SimpleChange([],component.resolvedBookIds,false)});
    expect(component.report()?.groups).toHaveLength(0);expect(component.inspected()).toBeNull();expect(api.scan).toHaveBeenCalledTimes(1);
  });

});
