import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
interface CatalogEvent {
  id: number;
  title: string;
  venue: string;
  price: number;
}

@Component({
  selector: 'app-event-catalog',
  imports: [RouterLink],
  templateUrl: './event-catalog.html',
  styleUrl: './event-catalog.css',
})
export class EventCatalog implements OnInit {
  private readonly http = inject(HttpClient);

  readonly events = signal<CatalogEvent[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly page = signal(0);
  readonly totalPages = signal(0);
  private readonly pageSize = 10;
  private keyword = '';
  ngOnInit(): void {
    this.search('');
  }

  search(keyword: string): void {
    this.keyword = keyword.trim();
    this.loadPage(0);
  }

  loadPage(page: number): void {
    this.loading.set(true);
    this.error.set('');

    this.http.get<CatalogEvent[]>('/api/events', {
      params: { keyword: this.keyword, page, size: this.pageSize },
      observe: 'response',
    }).subscribe({
      next: (response) => {
        this.events.set(response.body ?? []);
        this.page.set(page);

        const total = Number(response.headers.get('X-Total-Count') ?? '0');
        this.totalPages.set(Math.ceil(total / this.pageSize));
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger les événements.');
        this.loading.set(false);
      },
    });
  }
}
