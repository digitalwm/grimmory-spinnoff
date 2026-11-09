import {TestBed} from '@angular/core/testing';
import {HttpTestingController} from '@angular/common/http/testing';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {createQueryClientHarness} from '../../../core/testing/query-testing';
import {CatalogueGroup, CataloguePreview, CatalogueReconciliationService} from './catalogue-reconciliation.service';
import {BookSocketService} from './book-socket.service';
import {bookQueryKeys} from '../data/book-query-keys';

describe('CatalogueReconciliationService', () => {
  let service: CatalogueReconciliationService;
  let http: HttpTestingController;
  let harness: ReturnType<typeof createQueryClientHarness>;
  const group: CatalogueGroup = {key: 'shared', path: '/books/shared.epub', suggestedTargetBookId: 1,
    members: [{bookId: 1, fileId: 11, title: 'Book', authors: ['Ada'], storedHash: 'same'},
      {bookId: 2, fileId: 22, title: 'Book', authors: ['Ada'], storedHash: 'same'}]};
  const preview: CataloguePreview = {libraryId: 7, targetBookId: 1, members: group.members, path: group.path,
    fileSha256: 'verified', fileBytes: 123, eligible: true, reasons: [], relatedRecords: {}, metadataDifferences: {}, token: 'snapshot-token'};

  beforeEach(() => {
    harness = createQueryClientHarness();
    TestBed.configureTestingModule({providers: [...harness.providers, CatalogueReconciliationService]});
    service = TestBed.inject(CatalogueReconciliationService);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {http.verify(); TestBed.resetTestingModule();});

  it('scans a bounded library page without requesting deletion or merging', () => {
    service.scan(7, 2).subscribe();
    const request = http.expectOne(req => req.url.endsWith('/catalogue-reconciliation'));
    expect(request.request.method).toBe('GET');
    expect(request.request.params.get('libraryId')).toBe('7');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('20');
    request.flush({libraryId: 7, page: 2, size: 20, totalGroups: 0, extraRecords: 0, groups: []});
  });
  it('previews exactly the selected complete group and chosen survivor', () => {
    service.preview(7, group, 2).subscribe();
    const request = http.expectOne(req => req.url.endsWith('/preview'));
    expect(request.request.body).toEqual({libraryId: 7, bookIds: [1, 2], targetBookId: 2, identityConfirmed: false});
    request.flush({...preview, targetBookId: 2, identityConfirmed: false});
  });
  it('merges using the inspected token and invalidates book caches after success', () => {
    const invalidate = vi.spyOn(harness.queryClient, 'invalidateQueries');
    service.merge(preview).subscribe();
    const request = http.expectOne(req => req.url.endsWith('/merge'));
    expect(request.request.body).toEqual({libraryId: 7, bookIds: [1, 2], targetBookId: 1, token: 'snapshot-token', confirmed: true, identityConfirmed: false});
    request.flush({id: 'receipt', targetBookId: 1, removedBookIds: [2], fileSha256: 'verified', completedAt: '2026-11-07T00:00:00Z'});
    expect(invalidate).toHaveBeenCalledWith({queryKey: bookQueryKeys.all()});
  });
  it('sends one direct-merge request and invalidates books only when the server merged', () => {
    const invalidate=vi.spyOn(harness.queryClient,'invalidateQueries');
    service.quickMerge(7,group,1).subscribe();
    let request=http.expectOne(req=>req.url.endsWith('/quick-merge'));
    expect(request.request.body).toEqual({libraryId:7,bookIds:[1,2],targetBookId:1});
    request.flush({status:'REVIEW_REQUIRED',receipt:null,preview,reviewReasons:['Different editions']});expect(invalidate).not.toHaveBeenCalled();
    service.quickMerge(7,group,1).subscribe();request=http.expectOne(req=>req.url.endsWith('/quick-merge'));
    request.flush({status:'MERGED',receipt:{id:'receipt'},preview:null,reviewReasons:[]});expect(invalidate).toHaveBeenCalledTimes(1);
  });

  it('sends an explicit human decision in a single request using the selected target',()=>{
    service.confirmIdentityAndMerge(7,group,2).subscribe();
    const request=http.expectOne(req=>req.url.endsWith('/confirm-identity-and-merge'));
    expect(request.request.body).toEqual({libraryId:7,bookIds:[1,2],targetBookId:2,identityConfirmed:true});
    request.flush({status:'REVIEW_REQUIRED',receipt:null,preview,reviewReasons:['Conflicting progress']});
  });

  it('coalesces successful batch merges without invalidating library queries per group',()=>{
    const sockets=TestBed.inject(BookSocketService);const begin=vi.spyOn(sockets,'beginBatch');const end=vi.spyOn(sockets,'endBatch');
    const record=vi.spyOn(sockets,'recordCatalogueMerge');const invalidate=vi.spyOn(harness.queryClient,'invalidateQueries');
    service.beginBatch();service.beginBatch();service.quickMerge(7,group,1).subscribe();
    http.expectOne(req=>req.url.endsWith('/quick-merge')).flush({status:'MERGED',receipt:{id:'receipt',targetBookId:1,removedBookIds:[2]},reviewReasons:[]});
    expect(invalidate).not.toHaveBeenCalled();expect(record).toHaveBeenCalledWith(1,[2]);
    service.endBatch();service.endBatch();expect(begin).toHaveBeenCalledTimes(1);expect(end).toHaveBeenCalledTimes(1);
  });

});
