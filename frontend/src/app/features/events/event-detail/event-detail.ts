import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';

interface EventDetails {
  title: string;
  description: string | null;
  venue: string;
  startsAt: string;
  price: number;
}

@Component({
  selector: 'app-event-detail',
  imports: [DatePipe, RouterLink],
  templateUrl: './event-detail.html',
  styleUrl: './event-detail.css',
})
export class EventDetail implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);

  readonly event = signal<EventDetails | null>(null);
  readonly loading = signal(true);
  readonly error = signal('');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');

    if (!id || !/^\d+$/.test(id)) {
      this.error.set('Identifiant d’événement invalide.');
      this.loading.set(false);
      return;
    }

    this.http.get<EventDetails>(`/api/events/${id}`).subscribe({
      next: (event) => {
        this.event.set(event);
        this.loading.set(false);
      },
      error: (response) => {
        this.error.set(response.status === 404
          ? 'Événement introuvable.'
          : 'Impossible de charger cet événement.');
        this.loading.set(false);
      },
    });
  }
}
